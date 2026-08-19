#
# Copyright (C) 2020 Xilinx, Inc
#
# Licensed under the Apache License, Version 2.0 (the "License"). You may
# not use this file except in compliance with the License. A copy of the
# License is located at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.

# This file was originally a vitis RTL kernel example design.

set path_to_hdl "../design"
set path_to_packaged "./packaged_kernel_${suffix}"
set path_to_tmp_project "./tmp_kernel_pack_${suffix}"

# Upstream hardcodes the U250 part; the kernel-packaging project must use
# the part of the target platform or its IP is generated for the wrong device.
set projPart "xcu55c-fsvh2892-2L-e"

set base_filename "FireSim-generated"
set synth_xdc_path "${path_to_hdl}/${base_filename}.synthesis.xdc"
set impl_xdc_path  "${path_to_hdl}/${base_filename}.implementation.xdc"

# Establish reasonable bounds for the input frequency to catch gross errors.
# These numbers are somewhat arbitrary.
set min_frequency 1.0
set max_frequency 300.0

if {$frequency < ${min_frequency}  || $frequency > ${max_frequency}} {
  puts "Provided simulator frequency must be within \[$min_frequency, $max_frequency\] MHz.\n"
  puts "Provided value: $frequency MHz"
  exit 1
}

create_project -force kernel_pack $path_to_tmp_project -part ${projPart}
add_files -norecurse [ list \
    $path_to_hdl/defines.vh \
    $synth_xdc_path \
    $impl_xdc_path \
    $path_to_hdl/FireSim-generated.post-processed.sv \
]

# The design under test may resolve most of its RTL by library search during
# simulation, which Vivado has no equivalent for. When a flattened source list is
# supplied, every file in it is added explicitly and its search paths recorded.
if {[info exists ::env(VORTEX_VIVADO_SRCS)]} {
    set vortex_list $::env(VORTEX_VIVADO_SRCS)
    if {![file exists $vortex_list]} {
        puts "ERROR: VORTEX_VIVADO_SRCS points at a missing file: $vortex_list"
        exit 1
    }
    set fh [open $vortex_list r]
    set vortex_files {}
    set vortex_incdirs {}
    while {[gets $fh line] >= 0} {
        set line [string trim $line]
        if {$line eq ""} { continue }
        if {[string match "+incdir+*" $line]} {
            lappend vortex_incdirs [string range $line 8 end]
        } elseif {[string match "+*" $line]} {
            continue
        } else {
            lappend vortex_files $line
        }
    }
    close $fh
    if {[llength $vortex_files] == 0} {
        puts "ERROR: no sources listed in $vortex_list"
        exit 1
    }
    puts "collected [llength $vortex_files] design sources from $vortex_list"
    # Deliberately not added to this project. ipx::package_project infers bus
    # interfaces from every file it imports, and a design carrying SystemVerilog
    # interfaces cannot be expressed in IP-XACT -- packaging fails on ports it
    # has no way to represent. The files are attached to the packaged core
    # further down instead, so they ship with the IP without being parsed here.
    if {[llength $vortex_incdirs] > 0} {
        set_property include_dirs $vortex_incdirs [get_filesets sources_1]
    }
}

set_property USED_IN_SYNTHESIS false     [get_files $impl_xdc_path]
set_property USED_IN_IMPLEMENTATION true [get_files $impl_xdc_path]

update_compile_order -fileset sources_1
update_compile_order -fileset sim_1

# Note: The main MMCM generation script expects that frequency variable is defined
set ipgen_scripts [glob $path_to_hdl/FireSim-generated.*.ipgen.tcl]
foreach script $ipgen_scripts {
    source $script
}

# Use RTL models instead of systemC-based ones for Xilinx IP.
# - The AXI clock converter IP does not appear to function correctly when running on
#   millennium, which leads to lost transactions. and the RTL models are easier to debug
# - The RTL models are easier to read and debug
set_property SELECTED_SIM_MODEL rtl [get_ips]

generate_target all [get_ips]

ipx::package_project -root_dir $path_to_packaged -vendor xilinx.com -library RTLKernel -taxonomy /KernelIP -import_files -set_current false
ipx::unload_core $path_to_packaged/component.xml
ipx::edit_ip_in_project -upgrade true -name tmp_edit_project -directory $path_to_packaged $path_to_packaged/component.xml

set core [ipx::current_core]

set_property core_revision 2 $core
foreach up [ipx::get_user_parameters] {
    ipx::remove_user_parameter [get_property NAME $up] $core
}

ipx::associate_bus_interfaces -busif s_axi_lite -clock ap_clk $core
ipx::associate_bus_interfaces -busif host_mem_0 -clock ap_clk $core

# set up mem map for axis intf
set mem_map    [::ipx::add_memory_map -quiet "s_axi_lite" $core]
set addr_block [::ipx::add_address_block -quiet "reg0" $mem_map]

set host_mem_0_offset      [::ipx::add_register -quiet "host_mem_0_offset" $addr_block]
set_property address_offset 0x010 $host_mem_0_offset
set_property size           64    $host_mem_0_offset

set_property slave_memory_map_ref "s_axi_lite" [::ipx::get_bus_interfaces -of $core "s_axi_lite"]

# define association between pointer arguments (SRC_ADDR, DEST_ADDR) and axi masters (axi_rmst, axi_wmst)
ipx::add_register_parameter ASSOCIATED_BUSIF $host_mem_0_offset
set_property value {host_mem_0} [::ipx::get_register_parameters -of_objects $host_mem_0_offset ASSOCIATED_BUSIF]

ipx::add_bus_parameter DATA_WIDTH [ipx::get_bus_interfaces host_mem_0 -of_objects [ipx::current_core]]
set_property value          {64}  [ipx::get_bus_parameters DATA_WIDTH -of_objects [ipx::get_bus_interfaces host_mem_0 -of_objects [ipx::current_core]]]

set_property xpm_libraries {XPM_CDC XPM_MEMORY XPM_FIFO} $core
set_property sdx_kernel true $core
set_property sdx_kernel_type rtl $core
set_property supported_families { } $core
set_property auto_family_support_level level_2 $core
# Deliver the design sources with the packaged core. They are added to the
# synthesis file group directly rather than through the project, so synthesis
# sees them while the packager's interface inference never does.
if {[info exists vortex_files] && [llength $vortex_files] > 0} {
    # Both groups: synthesis builds the bitstream, behavioural simulation builds
    # hardware emulation, and a file registered in only one is invisible to the
    # other flow.
    set target_fgs [list \
        [ipx::get_file_groups xilinx_anylanguagesynthesis -of_objects $core] \
        [ipx::get_file_groups xilinx_anylanguagebehavioralsimulation -of_objects $core]]
    # Copied into the core rather than referenced where they lie: a path outside
    # the component root does not survive IP delivery, so the linker would find
    # the instantiation but not the module.
    set vortex_dst $path_to_packaged/src
    file mkdir $vortex_dst
    foreach vf $vortex_files {
        set base [file tail $vf]
        file copy -force $vf $vortex_dst/$base
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
    puts "attached [llength $vortex_files] design sources to the packaged core"
}

ipx::create_xgui_files $core
ipx::update_checksums $core
ipx::check_integrity -kernel $core
ipx::save_core $core
close_project -delete
