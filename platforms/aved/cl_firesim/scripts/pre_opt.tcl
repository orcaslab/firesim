# Runs on impl_1 immediately before opt_design (attached by attach_pre_opt.tcl).
#
# The simulator's clock gates only work on this platform if synthesis turned
# every one of them into clock enables. A cell the conversion left behind is
# clocked through a LUT -- a derived clock nothing times against the host clock
# the rest of the simulator runs on -- so the build stops here rather than
# produce an image that fails only on the card.

# Every clock pin in the kernel, and whatever drives its net. The check is
# structural because Vivado's own one (methodology TIMING-14, "LUT on the clock
# tree") does not fire on a netlist this early.
set clock_pins [get_pins -quiet -hierarchical -filter {IS_CLOCK && IS_LEAF && NAME =~ "top_i/slash/*"}]
set clock_nets [lsort -unique [get_nets -quiet -segments -of_objects $clock_pins]]
set gated [list]
foreach d [lsort -unique [get_cells -quiet -of_objects [get_pins -quiet -leaf -of_objects $clock_nets -filter {DIRECTION == OUT}]]] {
    if {[string match "LUT*" [get_property REF_NAME $d]]} {
        lappend gated $d
    }
}
if {[llength $gated] > 0} {
    foreach c [lrange $gated 0 19] {
        puts "ERROR: kernel clock driven by logic: $c"
    }
    error "pre_opt: [llength $gated] kernel clock net(s) are driven by LUTs; gated-clock conversion did not complete"
}
puts "INFO: pre_opt: no logic-driven clocks in the kernel"

if {[info exists ::env(VORTEX_PRE_OPT_TCL)] && $::env(VORTEX_PRE_OPT_TCL) ne ""} {
    set vortex_pre_opt [file normalize $::env(VORTEX_PRE_OPT_TCL)]
    if {![file exists $vortex_pre_opt]} {
        error "VORTEX_PRE_OPT_TCL names a file that does not exist: $vortex_pre_opt"
    }
    source $vortex_pre_opt
}
