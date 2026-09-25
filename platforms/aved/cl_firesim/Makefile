# Builds a FireSim simulator image (.vbin) for the AMD Alveo V80 with the SLASH
# linker, against the compute shell.
#
#   make bitstream TARGET=hw|sim DEVICE=<part> HOST_MEM_SP=<tag> FREQUENCY=<MHz>
#
# The design sources and vendor cores of the target under test are attached to
# the kernel through VORTEX_VIVADO_SRCS and VORTEX_XILINX_IP_DIR when set, and
# VORTEX_PRE_OPT_TCL runs before opt_design.

buildroot := $(abspath .)

PROJECT_NAME := firesim
# The linker names a kernel after its top module: the shim's.
KERNEL_NAME := AvedShim
KERNEL_INSTANCE := firesim_0

TARGET ?= hw
ifneq ($(TARGET),$(findstring $(TARGET), hw sim))
$(error TARGET must be hw or sim (got '$(TARGET)'))
endif

DEVICE ?= xcv80-lsva4737-2MHP-e-S

# Memory the host-memory port binds to. MEM routes through the HBM VNOC, 32 GB
# at 0x40_0000_0000: the target's whole 32-bit map. A single HBM channel (HBM0)
# is 1 GB and leaves the program image and stack unserved.
HOST_MEM_SP ?= MEM

# Simulator clock (MHz, fractional allowed). There is no MMCM: the shell programs the kernel clock
# to what the link records, and the simulator runs on it directly.
FREQUENCY ?= 60

SLASHKIT ?= slashkit
VIVADO := $(XILINX_VIVADO)/bin/vivado

BITSTREAM_DIR := $(buildroot)/bitstream
BUILD_DIR     := $(BITSTREAM_DIR)/build_dir.$(TARGET)
VBIN          := $(BUILD_DIR)/$(PROJECT_NAME).vbin
LINK_CFG      := $(BITSTREAM_DIR)/link.$(TARGET).cfg
PACKAGED      := $(BITSTREAM_DIR)/packaged_kernel/component.xml

# See the Vitis platform: the generated SV pulls its macro definitions in by an
# `include rather than through the tool's define mechanism.
sv_generated = design/FireSim-generated.sv
sv_processed = design/FireSim-generated.post-processed.sv
sv_defines   = design/defines.vh

$(sv_processed): $(sv_generated)
	echo "\`include \"$(notdir $(sv_defines))\"" > $@
	cat $< >> $@

.PHONY: bitstream package FORCE
bitstream: $(VBIN)
package: $(PACKAGED)

$(PACKAGED): scripts/package_kernel.tcl $(sv_processed) $(sv_defines)
	mkdir -p $(BITSTREAM_DIR)
	cd $(BITSTREAM_DIR) && $(VIVADO) -mode batch -nojournal -log package_kernel.log \
		-source $(buildroot)/scripts/package_kernel.tcl -tclargs $(DEVICE) $(BITSTREAM_DIR)

# Rewritten on every run and only when its content changes, so a changed
# binding or frequency relinks and an unchanged one does not.
$(LINK_CFG): FORCE
	@mkdir -p $(@D)
	@printf '%s\n' \
		'[connectivity]' \
		'shell=compute' \
		'nk=$(KERNEL_NAME):1:$(KERNEL_INSTANCE)' \
		'sp=$(KERNEL_INSTANCE).m_axi_host_mem:$(HOST_MEM_SP)' \
		'' \
		'[clock]' \
		'krnl=$(KERNEL_INSTANCE)' \
		"freqhz=$$(awk 'BEGIN { printf "%d", $(FREQUENCY) * 1000000 }')" > $@.tmp
	@if cmp -s $@.tmp $@; then rm -f $@.tmp; else mv -f $@.tmp $@; fi

# The pre-synthesis hook turns on gated-clock conversion and attaches
# scripts/pre_opt.tcl, which checks the conversion and then runs
# VORTEX_PRE_OPT_TCL when set (see the Vitis platform). The linker can only
# set run properties from a pre-synthesis hook.
$(VBIN): $(PACKAGED) $(LINK_CFG) scripts/attach_pre_opt.tcl scripts/pre_opt.tcl
	mkdir -p $(BUILD_DIR)
	$(SLASHKIT) link -c $(LINK_CFG) -p $(TARGET) -o $@ -k $(PACKAGED) --vivado $(VIVADO) \
		--pre-synth-tcls $(buildroot)/scripts/attach_pre_opt.tcl

FORCE:
