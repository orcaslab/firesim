# Sourced by the post-synthesis hook partition_synth.py writes for the
# kernel's synthesis run: each model synthesized on its own is a black box in
# the kernel's netlist there, and gets its finished netlist back.
#
# A black box of a design already in memory cannot take a netlist; the design
# is relinked from a checkpoint with each model's netlist read into its cells
# (the bottom-up flow), and the linked design replaces it as the current one.
#
# The kernel's run is an IP's out-of-context run, which has already prefixed
# every module with the IP's name (rename_ref -prefix_all, so that modules of
# different IPs cannot collide) and written its checkpoint when the hook runs:
# black boxes are found under either name, the netlists read in get the same
# prefix, and the run's checkpoint is rewritten before the run hands it on.

# parts: {module dcp module dcp ...}; tmp: a scratch directory.
proc fill_partitions {parts tmp} {
    set top [get_property TOP [current_design]]
    set part [get_property PART [current_design]]
    set prefix "${top}_"
    set cells {}
    foreach {module dcp} $parts {
        set found [get_cells -quiet -hierarchical -filter \
            "REF_NAME == $module || REF_NAME == $prefix$module"]
        if {[llength $found] == 0} {
            error "fill_partitions: no instance of $module in the kernel"
        }
        foreach c $found {
            if {![get_property IS_BLACKBOX $c]} {
                error "fill_partitions: $c ($module) was synthesized with the kernel, not left a black box"
            }
            lappend cells [get_property NAME $c] $dcp
        }
    }
    set prefixed [llength [get_cells -quiet -hierarchical -filter "REF_NAME =~ $prefix*"]]

    file mkdir $tmp
    write_checkpoint -force $tmp/kernel_bb.dcp
    close_design
    read_checkpoint $tmp/kernel_bb.dcp
    foreach {c dcp} $cells {
        read_checkpoint -cell $c $dcp
        puts "INFO: fill_partitions: $c <- $dcp"
    }
    link_design -mode out_of_context -top $top -part $part
    set left [get_cells -quiet -hierarchical -filter IS_BLACKBOX]
    if {[llength $left]} {
        error "fill_partitions: black boxes left after linking: [lrange $left 0 4]"
    }
    file delete -force $tmp/kernel_bb.dcp

    if {$prefixed} {
        set n 0
        foreach ref [lsort -unique [get_property REF_NAME \
                [get_cells -quiet -hierarchical -filter {IS_PRIMITIVE == 0}]]] {
            if {![string match $prefix* $ref]} {
                rename_ref -ref $ref -to $prefix$ref
                incr n
            }
        }
        puts "INFO: fill_partitions: prefixed $n module(s) of the partitions with $prefix"
    }
    if {[file exists $top.dcp]} {
        write_checkpoint -force -noxdef $top.dcp
        puts "INFO: fill_partitions: rewrote [file normalize $top.dcp]"
    }
}
