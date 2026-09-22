# Sourced by slashkit through --pre-synth-tcls, after the linker has created the
# project and its runs but before it launches them: the only point in the
# linker flow where implementation step properties can be set.
#
# Attaches VORTEX_PRE_OPT_TCL, when set, as impl_1's pre-opt_design hook; see
# the Vitis platform's Makefile.

if {[info exists ::env(VORTEX_PRE_OPT_TCL)] && $::env(VORTEX_PRE_OPT_TCL) ne ""} {
    set pre_opt [file normalize $::env(VORTEX_PRE_OPT_TCL)]
    if {![file exists $pre_opt]} {
        error "VORTEX_PRE_OPT_TCL names a file that does not exist: $pre_opt"
    }
    set_property STEPS.OPT_DESIGN.TCL.PRE $pre_opt [get_runs impl_1]
    puts "INFO: attached pre-opt hook to impl_1: $pre_opt"
}
