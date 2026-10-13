# Out-of-context synthesis of one module of the generated design, for the
# kernel's synthesis to link in (see partition_synth.py).
#
# Usage: vivado -mode batch -source synth_partition.tcl
#            -tclargs <src.sv> <module> <out_dir> <part> <hostClock period ns, 0: none> <directive> <threads>

lassign $argv src top out part period directive threads
# Each extra thread is a further Vivado process holding its own copy of the
# working set: the partition scheduler trades it against its memory budget.
set_param general.maxThreads $threads
file mkdir $out
read_verilog -sv $src
# A FireSim model is clocked by hostClock alone; constraining it lets
# synthesis time the model at the simulator clock.
if {$period > 0} {
    set fh [open $out/clock.xdc w]
    puts $fh "create_clock -period $period -name hostClock \[get_ports -quiet hostClock\]"
    close $fh
    read_xdc $out/clock.xdc
}
synth_design -top $top -part $part -mode out_of_context -directive $directive \
    -gated_clock_conversion on
report_utilization -file $out/util.rpt
# The shell's kernel clock must reach every flip-flop without passing through
# LUTs; a gate the conversion left behind fails here, in this model's own run.
set lut_clocks {}
foreach n [get_nets -quiet -hier -filter {TYPE == CLOCK}] {
    set d [get_cells -quiet -of [get_pins -quiet -leaf -of $n -filter {DIRECTION == OUT}]]
    if {[llength $d] && [string match "LUT*" [get_property REF_NAME $d]]} { lappend lut_clocks $n }
}
if {[llength $lut_clocks]} {
    error "synth_partition: $top: [llength $lut_clocks] clock nets driven by LUTs, e.g. [lindex $lut_clocks 0]"
}
write_checkpoint -force $out/$top.dcp
