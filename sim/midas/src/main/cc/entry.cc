// See LICENSE for license details.

#include <cstdio>
#include <memory>

#include "core/config.h"
#include "core/simif.h"
#include "core/simulation.h"

// NOLINTBEGIN
#define GET_INCLUDES
#include "FireSim-generated.const.h"
#undef GET_INCLUDES
#include "bridges/loadmem.h" // always ensure that loadmem.h header is present for tsibridge to use loadmem

#define GET_METASIM_INTERFACE_CONFIG
#define GET_SUBSTRUCT_CHECKS
#include "FireSim-generated.const.h"
#undef GET_SUBSTRUCT_CHECKS
// NOLINTEND

// The user-defined part of the driver implements this method to return
// a simulation instance implementing all simulation-specific logic.
extern std::unique_ptr<simulation_t>
create_simulation(simif_t &simif,
                  widget_registry_t &registry,
                  const std::vector<std::string> &args);

// The platform-specific component of the driver implements this method
// to return a handle to the simulation.
extern std::unique_ptr<simif_t>
create_simif(const TargetConfig &config, int argc, char **argv);

std::unique_ptr<simif_t> simulator; // must exist for dpi.cc calls in emulation,
                                    // must be global to avoid deletion
std::unique_ptr<simulation_t> simulation; // must be global to avoid deletion

// Entry point of the driver.
// Startup markers on stderr. Widget construction performs MMIO, and if the
// platform layer is not yet servicing it the process wedges here with no output
// at all -- indistinguishable from a slow model. stderr because stdout is
// block-buffered when redirected.
#define ENTRY_MARK(s_) do { fprintf(stderr, "[entry] " s_ "\n"); fflush(stderr); } while (0)

int entry(int argc, char **argv) {
  ENTRY_MARK("entered");
  // must have this exist for *.const.h
  std::vector<std::string> args(argv + 1, argv + argc);

  // Create the hardware interface.
  simulator = create_simif(conf_target, argc, argv);
  // must have this exist for *.const.h
  auto &simif = *simulator;
  ENTRY_MARK("simif created");

  /* clang-format off */
  // NOLINTBEGIN
  ENTRY_MARK("constructing widget registry");
  widget_registry_t *widget_registry_ptr = new widget_registry_t(); // must be never deallocated to avoid deletion
  // must have this for *.const.h
  widget_registry_t &registry = *widget_registry_ptr;
  {
    // Here we instantiate our driver once for each bridge in the target. The
    // generated header contains a list of constructor calls. The different
    // macros ensure that widgets that are required by other bridges are
    // initialized in the correct order: the master, clock and loadmem widgets
    // are built first, followed by stream engines and the rest of the bridges
    // of the design, which can make use of widgets.
    #define GET_CORE_CONSTRUCTOR
    #include "FireSim-generated.const.h"
    #undef GET_CORE_CONSTRUCTOR
    ENTRY_MARK("core widgets constructed");
    #define GET_MANAGED_STREAM_CONSTRUCTOR
    #include "FireSim-generated.const.h"
    #undef GET_MANAGED_STREAM_CONSTRUCTOR
    ENTRY_MARK("stream engines constructed");
    #define GET_BRIDGE_CONSTRUCTOR
    #include "FireSim-generated.const.h"
    #undef GET_BRIDGE_CONSTRUCTOR
  }
  // NOLINTEND
  /* clang-format on */

  ENTRY_MARK("bridges constructed");

  // Create the simulation instance.
  simulation = create_simulation(simif, *widget_registry_ptr, args);

  // Run the simulation with the given implementation.
  ENTRY_MARK("entering simif.run");
  const int rc = simif.run(*simulation);

  // Tear these down here rather than leaving them to static destruction. The
  // platform layer holds handles into libraries with statics of their own --
  // XRT's device registry among them -- and by the time exit handlers run those
  // may already be gone, so destroying the interface then walks freed state.
  // Order mirrors construction. Nothing below may use `simif`.
  simulation.reset();
  simulator.reset();
  return rc;
}
