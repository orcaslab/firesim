# Sourced by slashkit through --pre-synth-tcls, after the linker has created the
# project and its runs but before it launches them: the only point in the
# linker flow where synthesis and implementation step properties can be set.
#
# Two things are set here:
#  - gated-clock conversion on the simulator's synthesis run. Golden Gate's clock
#    gates are written for it on this platform (see FireSimFabricClockGate); the
#    simulator's IP is synthesized out of context in its own run, which does not
#    exist until it is created, so it is created here.
#  - pre_opt.tcl as impl_1's pre-opt_design hook: it rejects a netlist the
#    conversion did not fully clean, then runs VORTEX_PRE_OPT_TCL when set.

# The block design belongs to the reconfigurable module's own fileset, not to
# sources_1, so it is looked up across the project.
set bd [lsearch -all -inline -not -glob [get_files -quiet -all *.bd] */ip/*]
if {[llength $bd] == 0} {
    error "attach_pre_opt: no block design in the project"
}
foreach f $bd {
    if {[catch {create_ip_run $f} msg]} {
        puts "INFO: create_ip_run $f: $msg"
    }
}
set sim_runs [get_runs -quiet -filter {IS_SYNTHESIS && NAME =~ "*firesim_0*"}]
if {[llength $sim_runs] == 0} {
    error "attach_pre_opt: found no synthesis run for the FireSim kernel (firesim_0)"
}
foreach r $sim_runs {
    set_property STEPS.SYNTH_DESIGN.ARGS.GATED_CLOCK_CONVERSION on $r
    puts "INFO: gated-clock conversion enabled on $r"
    # Threaded FAME-5 kernels can exceed synth_design's capacity in the
    # cross-boundary optimization phase (abort at ~100 GB). RuntimeOptimized
    # skips that phase; set it only when the caller asks.
    if {[info exists ::env(VORTEX_SYNTH_DIRECTIVE)]} {
        set_property STEPS.SYNTH_DESIGN.ARGS.DIRECTIVE $::env(VORTEX_SYNTH_DIRECTIVE) $r
        puts "INFO: synth directive $::env(VORTEX_SYNTH_DIRECTIVE) on $r"
    }
    if {[info exists ::env(VORTEX_SYNTH_FLATTEN)]} {
        set_property STEPS.SYNTH_DESIGN.ARGS.FLATTEN_HIERARCHY $::env(VORTEX_SYNTH_FLATTEN) $r
        puts "INFO: synth flatten_hierarchy $::env(VORTEX_SYNTH_FLATTEN) on $r"
    }
}

# Near-full threaded payloads can leave route_design circling on a last node
# overlap under the default strategy's route directive; set only on request.
if {[info exists ::env(VORTEX_ROUTE_DIRECTIVE)]} {
    set_property STEPS.ROUTE_DESIGN.ARGS.DIRECTIVE $::env(VORTEX_ROUTE_DIRECTIVE) [get_runs impl_1]
    puts "INFO: route directive $::env(VORTEX_ROUTE_DIRECTIVE) on impl_1"
}

set pre_opt [file normalize [file join [file dirname [info script]] pre_opt.tcl]]
set_property STEPS.OPT_DESIGN.TCL.PRE $pre_opt [get_runs impl_1]
puts "INFO: attached pre-opt hook to impl_1: $pre_opt"
