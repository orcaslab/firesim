# Package the FireSim simulator as an IP-XACT RTL kernel for the SLASH linker,
# which consumes component.xml directly.
#
# Usage: vivado -mode batch -source package_kernel.tcl -tclargs <part> <out_dir>

if { $::argc != 2 } {
    puts "ERROR: usage: package_kernel.tcl <part> <out_dir>"
    exit 1
}
set part     [lindex $::argv 0]
set out_dir  [lindex $::argv 1]

set path_to_hdl          "../design"
set path_to_packaged     "${out_dir}/packaged_kernel"
set path_to_tmp_project  "${out_dir}/tmp_kernel_pack"
set base_filename        "FireSim-generated"
set synth_xdc_path       "${path_to_hdl}/${base_filename}.synthesis.xdc"
set impl_xdc_path        "${path_to_hdl}/${base_filename}.implementation.xdc"

create_project -force kernel_pack $path_to_tmp_project -part $part
add_files -norecurse [list \
    $path_to_hdl/defines.vh \
    $synth_xdc_path \
    $impl_xdc_path \
    $path_to_hdl/FireSim-generated.post-processed.sv \
]
set_property USED_IN_SYNTHESIS false     [get_files $impl_xdc_path]
set_property USED_IN_IMPLEMENTATION true [get_files $impl_xdc_path]
set_property top AvedShim [current_fileset]

# The design under test's flattened sources and the vendor cores they
# instantiate. Withheld from this project -- the packager infers interfaces from
# every file it imports -- and attached to the packaged core below.
set design_files {}
set design_incdirs {}
if {[info exists ::env(VORTEX_VIVADO_SRCS)]} {
    set fh [open $::env(VORTEX_VIVADO_SRCS) r]
    while {[gets $fh line] >= 0} {
        set line [string trim $line]
        if {$line eq ""} { continue }
        if {[string match "+incdir+*" $line]} {
            lappend design_incdirs [string range $line 8 end]
        } elseif {![string match "+*" $line]} {
            lappend design_files $line
        }
    }
    close $fh
    if {[llength $design_files] == 0} {
        puts "ERROR: no sources listed in $::env(VORTEX_VIVADO_SRCS)"
        exit 1
    }
}
set design_ips {}
if {[info exists ::env(VORTEX_XILINX_IP_DIR)]} {
    set design_ips [glob -nocomplain $::env(VORTEX_XILINX_IP_DIR)/*/*.xci]
    if {[llength $design_ips] == 0} {
        puts "ERROR: VORTEX_XILINX_IP_DIR has no generated cores: $::env(VORTEX_XILINX_IP_DIR)"
        exit 1
    }
}
if {[llength $design_incdirs] > 0} {
    set_property include_dirs $design_incdirs [get_filesets sources_1]
}
update_compile_order -fileset sources_1

ipx::package_project -root_dir $path_to_packaged -vendor firesim -library RTLKernel \
    -taxonomy /KernelIP -import_files -set_current false
ipx::unload_core $path_to_packaged/component.xml
ipx::edit_ip_in_project -upgrade true -name tmp_edit_project \
    -directory $path_to_packaged $path_to_packaged/component.xml
set core [ipx::current_core]
set_property core_revision 1 $core
foreach up [ipx::get_user_parameters] {
    ipx::remove_user_parameter [get_property NAME $up] $core
}

ipx::infer_bus_interfaces xilinx.com:interface:aximm_rtl:1.0 $core
ipx::associate_bus_interfaces -busif s_axi_control -clock ap_clk $core
ipx::associate_bus_interfaces -busif m_axi_host_mem -clock ap_clk $core

# The linker places a kernel by the register block of its AXI-Lite map. FireSim's
# 4 KiB control space is spread over 16 KiB by the shim's 16-byte stride.
set mem_map    [ipx::add_memory_map -quiet s_axi_control $core]
set addr_block [ipx::add_address_block -quiet reg0 $mem_map]
set_property range 16384 $addr_block
set_property usage register $addr_block
set_property slave_memory_map_ref s_axi_control [ipx::get_bus_interfaces s_axi_control -of_objects $core]

# The memory master issues neither wrap/fixed nor narrow bursts.
set mbif [ipx::get_bus_interfaces m_axi_host_mem -of_objects $core]
foreach param {HAS_BURST SUPPORTS_NARROW_BURST} {
    if {[llength [ipx::get_bus_parameters $param -of_objects $mbif]] == 0} {
        ipx::add_bus_parameter $param $mbif
    }
    set_property value 0 [ipx::get_bus_parameters $param -of_objects $mbif]
}
if {[llength [ipx::get_bus_parameters DATA_WIDTH -of_objects $mbif]] == 0} {
    ipx::add_bus_parameter DATA_WIDTH $mbif
}
set_property value 64 [ipx::get_bus_parameters DATA_WIDTH -of_objects $mbif]

set target_fgs [list \
    [ipx::get_file_groups xilinx_anylanguagesynthesis -of_objects $core] \
    [ipx::get_file_groups xilinx_anylanguagebehavioralsimulation -of_objects $core]]
file mkdir $path_to_packaged/src
foreach f $design_files {
    set base [file tail $f]
    file copy -force $f $path_to_packaged/src/$base
    foreach fg $target_fgs {
        set added [ipx::add_file src/$base $fg]
        if {[string match "*.vh" $base] || [string match "*.svh" $base]} {
            set_property type verilogSource $added
            set_property is_include true $added
        } elseif {[string match "*.sv" $base]} {
            set_property type systemVerilogSource $added
        }
    }
}
if {[llength $design_files] > 0} {
    puts "attached [llength $design_files] design sources to the packaged core"
}
file mkdir $path_to_packaged/src/ip
foreach xci $design_ips {
    set name [file rootname [file tail $xci]]
    # Into a fresh directory: onto an existing one, file copy nests the source
    # inside it and the stale core from the previous package stays referenced.
    file delete -force $path_to_packaged/src/ip/$name
    file copy -force [file dirname $xci] $path_to_packaged/src/ip/$name
    foreach fg $target_fgs {
        set added [ipx::add_file src/ip/$name/$name.xci $fg]
        set_property type xci $added
    }
}
if {[llength $design_ips] > 0} {
    puts "attached [llength $design_ips] design IP cores to the packaged core"
}

set_property xpm_libraries {XPM_CDC XPM_MEMORY XPM_FIFO} $core
set_property sdx_kernel true $core
set_property sdx_kernel_type rtl $core
ipx::create_xgui_files $core
ipx::update_checksums $core
ipx::check_integrity -kernel $core
ipx::save_core $core
close_project -delete
