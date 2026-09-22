#include <algorithm>
#include <cassert>
#include <stdexcept>
#include <string>
#include <vector>

#include <fcntl.h>
#include <iostream>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include "bridges/fpga_managed_stream.h"
#include "core/simif.h"

#include "experimental/xrt_device.h"
#include "experimental/xrt_ip.h"
#include "experimental/xrt_kernel.h"
#include "experimental/xrt_xclbin.h"

class simif_vitis_t final : public simif_t, public FPGAManagedStreamIO {
public:
  simif_vitis_t(const TargetConfig &config,
                const std::vector<std::string> &args);
  ~simif_vitis_t() {}

  void write(size_t addr, uint32_t data) override;
  uint32_t read(size_t addr) override;

  uint32_t is_write_ready();

  FPGAManagedStreamIO &get_fpga_managed_stream_io() override { return *this; }

private:
  uint32_t mmio_read(size_t addr) override { return read(addr); }

  void mmio_write(size_t addr, uint32_t value) override {
    return write(addr, value);
  }

  char *get_memory_base() override;

private:
  int slotid;
  std::string binary_file;
  xrt::device device_handle;
  xrt::uuid uuid;
  xrt::ip kernel_handle;
  xrt::run run_handle;
  /// The device-side memory backing host_mem_0. Must outlive the
  /// simulation: as a constructor local its allocation is freed on
  /// return, leaving the window pointing at memory XRT may reuse.
  xrt::bo fpga_mem_0;
};

/// Size of the device-memory window backing host_mem_0.
///
/// This must cover the target's whole address map, not merely the part of it a
/// given test happens to touch. A 32-bit target places its stack at the top of
/// 4 GiB and its program image well above any buffer it allocates, so anything
/// smaller leaves those accesses unserved: writes are dropped and reads return
/// an address-independent value, with no error anywhere to say so.
///
/// One U55C HBM pseudo-channel is 512 MB, so 4 GiB spans eight of them and
/// `--connectivity.sp` must bind host_mem_0 to a matching range (HOST_MEM_SP in
/// the Vitis kernel Makefile). The two are a pair: widening one alone silently
/// truncates.
constexpr size_t host_mem_0_size = 4ULL * 1024 * 1024 * 1024;

/// Allocates the device-memory window backing host_mem_0, choosing a memory
/// region that can hold all of it. Tries the largest usable regions first so a
/// range group is preferred over any single pseudo-channel inside it.
static xrt::bo allocate_host_mem(const xrt::device &device,
                                 const std::string &binary_file,
                                 size_t bytes) {
  xrt::xclbin xclbin(binary_file);
  const auto mems = xclbin.get_mems();

  std::vector<size_t> candidates;
  for (size_t i = 0; i < mems.size(); ++i) {
    if (mems[i].get_used()) {
      candidates.push_back(i);
    }
  }
  std::stable_sort(candidates.begin(), candidates.end(),
                   [&mems](size_t a, size_t b) {
                     return mems[a].get_size_kb() > mems[b].get_size_kb();
                   });

  for (size_t idx : candidates) {
    try {
      xrt::bo bo(device, bytes, xrt::bo::flags::device_only, idx);
      std::cerr << "[vitis] host_mem_0 backed by memory index " << idx << " ("
                << mems[idx].get_tag() << ")" << std::endl;
      return bo;
    } catch (const std::exception &) {
      // Too small, or not allocatable: try the next region.
    }
  }

  std::cerr << "[vitis] no memory region can back a 0x" << std::hex << bytes
            << std::dec << "-byte host_mem_0 window. Topology:" << std::endl;
  for (size_t i = 0; i < mems.size(); ++i) {
    std::cerr << "[vitis]   [" << i << "] " << mems[i].get_tag() << " "
              << mems[i].get_size_kb() << " KB"
              << (mems[i].get_used() ? " (used)" : "") << std::endl;
  }
  throw std::runtime_error("host_mem_0 window does not fit any memory region; "
                           "widen --connectivity.sp (HOST_MEM_SP)");
}

simif_vitis_t::simif_vitis_t(const TargetConfig &config,
                             const std::vector<std::string> &args)
    : simif_t(config) {
  slotid = -1;
  binary_file = "";

  // TODO: Properly read out arguments
  for (auto &arg : args) {
    if (arg.find("+slotid=") == 0) {
      slotid = atoi((arg.c_str()) + 8);
    }
    if (arg.find("+binary_file=") == 0) {
      binary_file = arg.substr(
          13, std::string::npos); //"kernel.xclbin"; //atoi((arg.c_str()) + 8);
    }
  }

  if (slotid == -1) {
    fprintf(stderr, "Device ID not specified. Assuming Device 0.\n");
    slotid = 0;
  }

  if (binary_file == "") {
    fprintf(stderr, "No binary file specified.\n");
    exit(1);
  }

  fprintf(stdout, "DEBUG: DevIdx:%d XCLBin:%s\n", slotid, binary_file.c_str());

  // Open the FPGA device
  device_handle = xrt::device(slotid);

  // Load the XCLBIN (get handle, then load)
  uuid = device_handle.load_xclbin(binary_file);

  // Open Kernel
  kernel_handle = xrt::ip(device_handle, uuid, "firesim");

  // Intialize FPGA-DRAM regions.
  //
  // The last argument is a memory index into the xclbin's topology. Binding
  // host_mem_0 to a range of pseudo-channels makes the region a memory *group*
  // whose index is not knowable from the port name, and xrt::ip -- unlike
  // xrt::kernel -- exposes no group_id() to ask for it. So try the indices the
  // xclbin reports until one can back the whole window, and fail loudly with
  // the topology printed if none can. Quietly settling for a smaller region is
  // the exact failure this window size exists to prevent.
  fpga_mem_0 = allocate_host_mem(device_handle, binary_file, host_mem_0_size);

  // NOTE: the kernel's host_mem_0_offset register (package_kernel.tcl, s_axi_lite
  // 0x010) is IP-XACT metadata only -- it does not appear in the generated RTL,
  // and writing it would land in FireSim's own widget MMIO space. Address
  // mapping into HBM is done by the platform memory subsystem via
  // --connectivity.sp, so nothing needs programming here.
  //
  // The shim adds a base fixed at elaboration (conf.mem_base) to every
  // host_mem_0 access. If the memory the binding actually resolves to starts
  // anywhere else, every access misses it: reads return a fixed bus default
  // and writes are dropped, with the control plane still working.
  std::cerr << "[vitis] host_mem_0 base = 0x" << std::hex
            << fpga_mem_0.address() << " size = 0x" << host_mem_0_size
            << std::dec << std::endl;
  if (fpga_mem_0.address() != config.mem_base) {
    std::cerr << "[vitis] host_mem_0 is at 0x" << std::hex
              << fpga_mem_0.address() << " but the shim was elaborated for 0x"
              << config.mem_base << std::dec
              << "; rebuild with the platform's memory base" << std::endl;
    throw std::runtime_error("host_mem_0 base does not match the elaborated shim");
  }
}

void simif_vitis_t::write(size_t addr, uint32_t data) {
  // addr is really a (32-byte) word address because of zynq implementation
  // addr <<= CTRL_AXI4_SIZE;
  kernel_handle.write_register(addr, data);
}

uint32_t simif_vitis_t::read(size_t addr) {
  // Convert the word address into a byte-address
  // addr <<= CTRL_AXI4_SIZE;
  uint32_t value;
  value = kernel_handle.read_register(addr);
  return value & 0xFFFFFFFF;
}

uint32_t simif_vitis_t::is_write_ready() {
  uint64_t addr = 0x4;
  uint32_t value;
  value = kernel_handle.read_register(addr);
  return value & 0xFFFFFFFF;
}

char *simif_vitis_t::get_memory_base() {
  std::cerr << "FPGA-managed streams are not yet supported";
  abort();
}

std::unique_ptr<simif_t>
create_simif(const TargetConfig &config, int argc, char **argv) {
  std::vector<std::string> args(argv + 1, argv + argc);
  return std::make_unique<simif_vitis_t>(config, args);
}
