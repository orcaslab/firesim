// FireSim platform layer for the AMD Alveo V80 through the SLASH compute shell.
//
// Control is MMIO only, through VRT's kernel register interface; the target's
// DRAM is reached by the shim's m_axi_host_mem port and loaded through the
// LoadMem widget, so the host never maps device memory itself.

#include <cstdio>
#include <cstdlib>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "bridges/fpga_managed_stream.h"
#include "core/simif.h"

#include <vrt/device.hpp>
#include <vrt/kernel.hpp>

namespace {

/// Kernel instance name the FireSim link config assigns.
constexpr const char *kernel_name = "firesim_0";

/// Name of the shim's memory port in the vbin's connection map.
constexpr const char *host_mem_port = "m_axi_host_mem";

/// Where the compute shell maps the HBM VNOC (the linker's MEM tag): 32 GB at
/// 0x40_0000_0000. VRT reports a port's memory type but not its address, so
/// the elaborated base is checked against the type's known base.
constexpr uint64_t hbm_vnoc_base = 0x4000000000ULL;

/// AvedShim gives every control register its own 16-byte block
/// (AXI4LiteStride4): FireSim register byte address a is at 4 * a.
constexpr size_t ctrl_stride = 4;

/// SimulationMaster's reset value of PRESENCE_READ ("Fire").
constexpr uint32_t fingerprint = 0x46697265;

/// What a read returns when the PCIe completion times out on this platform.
constexpr uint32_t no_completion = 0xFFFFFFFF;

} // namespace

class simif_aved_t final : public simif_t {
public:
  simif_aved_t(const TargetConfig &config, const std::vector<std::string> &args);

  void write(size_t addr, uint32_t data) override;
  uint32_t read(size_t addr) override;

private:
  void check_clock();
  void check_transport();
  void check_host_mem();
  void park_clock() noexcept;

  std::string bdf;
  std::string binary_file;
  vrt::Device device;
  vrt::Kernel kernel;
};

simif_aved_t::simif_aved_t(const TargetConfig &config,
                           const std::vector<std::string> &args)
    : simif_t(config) {
  for (auto &arg : args) {
    if (arg.find("+bdf=") == 0) {
      bdf = arg.substr(5);
    }
    if (arg.find("+binary_file=") == 0) {
      binary_file = arg.substr(13);
    }
  }
  if (bdf.empty() || binary_file.empty()) {
    std::cerr << "[aved] +bdf=<bus:dev> and +binary_file=<vbin> are both required"
              << std::endl;
    exit(1);
  }

  std::cerr << "[aved] programming " << bdf << " with " << binary_file << std::endl;
  device = vrt::Device(bdf, binary_file, /*program=*/true);
  kernel = vrt::Kernel(device, kernel_name);

  // A target that fails its checks stays loaded; leave it clocked below any
  // image's timing so it cannot disturb the shell before the next load.
  try {
    check_clock();
    check_transport();
    check_host_mem();
  } catch (...) {
    park_clock();
    throw;
  }
}

void simif_aved_t::park_clock() noexcept {
  constexpr uint64_t kSafeHz = 50000000;
  try {
    if (device.getPlatform() == vrt::Platform::HARDWARE) {
      device.setFrequency(kSafeHz);
      std::cerr << "[aved] kernel clock parked at " << device.getFrequency() << " Hz" << std::endl;
    }
  } catch (...) {
  }
}

// The shell boots the kernel clock at its own rate, not at the one the image
// was timed for, and only a VRT that programs the clock at open fixes that --
// an older one leaves it, and the image then runs past its timing with nothing
// to show for it but a target that misbehaves. So set the clock here and read
// the wizard back before anything reaches the target.
void simif_aved_t::check_clock() {
  if (device.getPlatform() != vrt::Platform::HARDWARE) {
    return;
  }
  const uint64_t timed_hz = device.getMaxFrequency();
  if (timed_hz == 0) {
    throw std::runtime_error("[aved] the image records no kernel clock frequency");
  }
  // The wizard settles on the nearest rate its dividers reach, which can be
  // a step above the request: lower the request below that step until the
  // rate read back is one the image met.
  uint64_t want_hz = timed_hz, actual_hz = 0;
  for (int i = 0; i < 8; ++i) {
    device.setFrequency(want_hz);
    actual_hz = device.getFrequency();
    if (actual_hz == 0 || actual_hz <= timed_hz) {
      break;
    }
    want_hz -= (actual_hz - want_hz) + timed_hz / 100;
  }
  std::cerr << "[aved] kernel clock " << actual_hz << " Hz (image timed at "
            << timed_hz << " Hz)" << std::endl;
  if (actual_hz == 0 || actual_hz > timed_hz) {
    throw std::runtime_error("[aved] the kernel clock is not at the image's "
                             "timed frequency; refusing to run the target");
  }
}

// Proves the control path before any widget touches the device. A fixed
// expected value separates the failure modes a counter could not: a dead bus
// (the completion-timeout pattern), a decode error, and a different image.
void simif_aved_t::check_transport() {
  const uint32_t first = read(config.master_presence_read);
  if (first == no_completion) {
    throw std::runtime_error("[aved] control reads return 0xFFFFFFFF (no PCIe "
                             "completion): the kernel is not answering");
  }
  if (first != fingerprint) {
    char msg[160];
    snprintf(msg, sizeof(msg),
             "[aved] PRESENCE_READ is 0x%08x, expected 0x%08x: not a FireSim "
             "image, or the control map is misaddressed",
             first, fingerprint);
    throw std::runtime_error(msg);
  }

  // The write path: a different value must read back, then the fingerprint is
  // restored so the simulation sees a pristine master.
  const uint32_t probe = ~fingerprint;
  write(config.master_presence_write, probe);
  const uint32_t echoed = read(config.master_presence_read);
  write(config.master_presence_write, fingerprint);
  if (echoed != probe || read(config.master_presence_read) != fingerprint) {
    throw std::runtime_error("[aved] PRESENCE_WRITE did not read back: control "
                             "writes are not reaching the simulator");
  }
  std::cerr << "[aved] control path verified" << std::endl;
}

// The shim adds a base fixed at elaboration to every memory access; if the
// link bound the port anywhere else, every access misses, reads return a bus
// default and writes are dropped, with the control path still working.
void simif_aved_t::check_host_mem() {
  const vrt::MemoryConfig mem = kernel.portMemoryConfig(host_mem_port);
  if (mem.type != vrt::MemoryRangeType::HBM_VNOC) {
    throw std::runtime_error("[aved] m_axi_host_mem is not bound to the HBM "
                             "VNOC (link it with sp=...:MEM)");
  }
  if (config.mem_base != hbm_vnoc_base) {
    char msg[160];
    snprintf(msg, sizeof(msg),
             "[aved] shim elaborated for memory base 0x%llx, but the HBM VNOC "
             "is at 0x%llx",
             (unsigned long long)config.mem_base,
             (unsigned long long)hbm_vnoc_base);
    throw std::runtime_error(msg);
  }
}

void simif_aved_t::write(size_t addr, uint32_t data) {
  kernel.write(static_cast<uint32_t>(addr * ctrl_stride), data);
}

uint32_t simif_aved_t::read(size_t addr) {
  return kernel.read(static_cast<uint32_t>(addr * ctrl_stride));
}

std::unique_ptr<simif_t>
create_simif(const TargetConfig &config, int argc, char **argv) {
  std::vector<std::string> args(argv + 1, argv + argc);
  return std::make_unique<simif_aved_t>(config, args);
}
