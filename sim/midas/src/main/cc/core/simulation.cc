// See LICENSE for license details.

#include "simulation.h"
#include "bridges/clock.h"
#include "bridges/loadmem.h"
#include "bridges/master.h"
#include "core/bridge_driver.h"
#include "core/simif.h"
#include "core/stream_engine.h"
#include "core/timing.h"

#include <cassert>
#include <cinttypes>
#include <cstdio>

simulation_t::simulation_t(widget_registry_t &registry,
                           const std::vector<std::string> &args)
    : registry(registry), clock(registry.get_widget<clockmodule_t>()) {
  bool fastloadmem = false;
  for (auto &arg : args) {
    if (arg.find("+fastloadmem") == 0) {
      fastloadmem = true;
    }
    if (arg.find("+loadmem=") == 0) {
      load_mem_path = arg.c_str() + 9;
    }
    if (arg.find("+memcheck") == 0) {
      do_memcheck = true;
    }
    if (arg.find("+zero-out-dram") == 0) {
      do_zero_out_dram = true;
    }
    if (arg.find("+check-fingerprint") == 0) {
      check_fingerprint_only = true;
    }
    if (arg.find("+write-fingerprint=") == 0) {
      write_fingerprint_only = atoi(arg.c_str() + 19);
    }
  }

  if (fastloadmem)
    load_mem_path.clear();
}

void simulation_t::record_start_times() {
  start_hcycle = clock.hcycle();
  start_tcycle = clock.tcycle();
  start_time = timestamp();
}

void simulation_t::record_end_times() {
  end_time = timestamp();
  end_tcycle = clock.tcycle();
  end_hcycle = clock.hcycle();
}

void simulation_t::print_simulation_performance_summary() {
  // Must call record_start_times and record_end_times before invoking this
  // function
  assert(start_hcycle.has_value() && end_hcycle.has_value() &&
         "simulation not executed");

  const uint64_t hcycles = *end_hcycle - *start_hcycle;
  const double sim_time = diff_secs(end_time, start_time);
  const uint64_t tcycles = elapsed_tcycles();
  const double sim_speed = ((double)tcycles) / (sim_time * 1000.0);
  const double measured_host_frequency =
      ((double)hcycles) / (sim_time * 1000.0);
  // A run that steps no target cycles is legal (e.g. fingerprint-only);
  // reporting inf/nan for it would be worse than reporting zero.
  const double fmr = tcycles ? ((double)hcycles / tcycles) : 0.0;

  fprintf(stderr, "\nEmulation Performance Summary\n");
  fprintf(stderr, "------------------------------\n");
  fprintf(stderr, "Wallclock Time Elapsed: %.1f s\n", sim_time);
  // Provide enough sig-figs to let the report be useful in RTL sim
  fprintf(stderr, "Host Frequency: ");
  if (measured_host_frequency > 1000.0) {
    fprintf(stderr, "%.3f MHz\n", measured_host_frequency / 1000.0);
  } else {
    fprintf(stderr, "%.3f KHz\n", measured_host_frequency);
  }

  fprintf(stderr, "Target Cycles Emulated: %" PRIu64 "\n", tcycles);
  fprintf(stderr, "Effective Target Frequency: ");
  if (sim_speed > 1000.0) {
    fprintf(stderr, "%.3f MHz\n", sim_speed / 1000.0);
  } else {
    fprintf(stderr, "%.3f KHz\n", sim_speed);
  }
  fprintf(stderr, "FMR: %.2f\n", fmr);
  fprintf(stderr,
          "Note: The latter three figures are based on the fastest "
          "target clock.\n");
}

void simulation_t::simulation_init() {
  for (auto *bridge : registry.get_all_bridges()) {
    bridge->init();
  }
}

void simulation_t::simulation_finish() {
  for (auto *bridge : registry.get_all_bridges()) {
    bridge->finish();
  }
}

int simulation_t::execute_simulation_flow() {
  fprintf(stderr, "entered simulation flow execution\n");
  wait_for_init();
  fprintf(stderr, "finished waiting\n");

  // following fingerprint logic uses 'exit' instead of 'return' to avoid
  // issues w/ deconstructors not having initialized values
  auto &master = registry.get_widget<master_t>();
  if (check_fingerprint_only || write_fingerprint_only.has_value()) {
    if (check_fingerprint_only && write_fingerprint_only.has_value()) {
      fprintf(stderr, "Unable to both check/write FireSim fingerprint\n");
      exit(EXIT_FAILURE);
    }

    if (check_fingerprint_only && master.check_fingerprint()) {
      fprintf(stderr, "Invalid FireSim fingerprint\n");
      exit(EXIT_FAILURE);
    }
    if (write_fingerprint_only.has_value()) {
      master.write_fingerprint(write_fingerprint_only.value());
    }
    exit(EXIT_SUCCESS);
  } else {
    if (master.check_fingerprint()) {
      fprintf(stderr, "Invalid FireSim fingerprint\n");
      exit(EXIT_FAILURE);
    }
  }

  if (auto *fpga_stream = registry.get_fpga_stream_engine()) {
    fpga_stream->init();
  }

  if (auto *stream = registry.get_stream_engine()) {
    stream->init();
  }

  init_dram();

  simulation_init();

  record_start_times();
  fprintf(stderr, "Commencing simulation.\n");
  const int exit_code = simulation_run();
  fprintf(stderr, "\nSimulation complete.\n");
  record_end_times();

  simulation_finish();

  const bool timeout = simulation_timed_out();

  if (exit_code != 0) {
    fprintf(stderr,
            "*** FAILED *** (code = %d) after %" PRIu64 " cycles\n",
            exit_code,
            elapsed_tcycles());
  } else if (timeout) {
    fprintf(stderr,
            "*** FAILED *** simulation timed out after %" PRIu64 " cycles\n",
            elapsed_tcycles());
  } else {
    fprintf(stderr, "*** PASSED *** after %" PRIu64 " cycles\n", elapsed_tcycles());
  }

  print_simulation_performance_summary();

  return timeout ? EXIT_FAILURE : exit_code;
}

void simulation_t::wait_for_init() {
  auto &master = registry.get_widget<master_t>();
  while (!master.is_init_done())
    ;
}

void simulation_t::init_dram() {
  if (auto *loadmem = registry.get_widget_opt<loadmem_t>()) {
    if (do_zero_out_dram) {
      fprintf(stderr,
              "Zeroing out FPGA DRAM. This will take a few seconds...\n");
      loadmem->zero_out_dram();
    }

    if (!load_mem_path.empty()) {
      loadmem->load_mem_from_file(load_mem_path);
    }

    // Diagnostic: write a known pattern through the LoadMem widget and read it
    // back through the same widget. This isolates "the target DRAM path works"
    // from any question about the design under test -- if this round-trip
    // fails, no memory-touching target can possibly pass.
    if (do_memcheck) {
      const size_t chunk_bits = loadmem->get_mem_data_chunk() * 8;
      mpz_t wr, rd;
      mpz_init(wr);
      mpz_init(rd);
      unsigned failures = 0;
      // Probes must span the target's whole address map, not just its bottom.
      // An earlier list stopped below 1 MB and so passed against a window that
      // served only its first eighth, while the target's program image and
      // stack -- which sit at 0x80000000 and just under 4 GiB -- landed
      // outside it. Proving the path works says nothing about how far it
      // reaches; these last three are what test the reach.
      const size_t probes[] = {0x0,
                               0x40,
                               0x1000,
                               0x40000,
                               0xF0000,
                               0x20000000,
                               0x80000000,
                               0xFFFF0000};
      for (size_t addr : probes) {
        // A value that is distinct per address and not all-ones/all-zeros.
        mpz_set_ui(wr, 0);
        mpz_setbit(wr, 0);
        mpz_mul_2exp(wr, wr, chunk_bits / 2);
        mpz_add_ui(wr, wr, (unsigned long)(0xA5A50000u | (addr & 0xFFFF)));
        loadmem->write_mem(addr, wr);
        loadmem->read_mem(addr, rd);
        const bool ok = (mpz_cmp(wr, rd) == 0);
        gmp_fprintf(stderr,
                    "[memcheck] addr=0x%zx wrote=0x%Zx read=0x%Zx %s\n",
                    addr,
                    wr,
                    rd,
                    ok ? "OK" : "MISMATCH");
        if (!ok) {
          failures++;
        }
      }
      mpz_clear(wr);
      mpz_clear(rd);
      fprintf(stderr,
              "[memcheck] %s (%u/%zu probes failed)\n",
              failures ? "TARGET DRAM PATH IS BROKEN" : "target DRAM path OK",
              failures,
              sizeof(probes) / sizeof(probes[0]));
    }
  } else {
    if (do_zero_out_dram || !load_mem_path.empty()) {
      fprintf(stderr,
              "Skipping memory initialization: target does not use DRAM\n");
    }
  }
}
