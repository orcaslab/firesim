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
FREQUENCY ?= 143

# Models synthesized on their own, under a memory budget, before the kernel
# (scripts/partition_synth.py): the kernel's synthesis sees them as black boxes
# and links their netlists back in. A threaded FAME-5 simulator synthesized in
# one piece peaks past 100 GB; split at its models, the peak is the largest
# model's. SYNTH_MEM_BUDGET (GB) is a hard cap on the whole step: models run
# side by side while their expected peaks fit in it, and one that does not fit
# alone fails the build by name. Entries are module names or glob patterns:
# '*_threaded_*' is every FAME-5 threaded model.
SYNTH_PARTITIONS ?=
SYNTH_MEM_BUDGET ?= 40
SYNTH_PARTITION_JOBS ?= 4
SYNTH_PARTITION_THREADS ?= 2
# Default, not RuntimeOptimized: RuntimeOptimized maps no multiplier to a DSP
# (the TCU model's 128 mantissa multipliers: 0 DSP and 92K LUTs vs 128 DSP and
# 63K LUTs), so the models would not keep the RTL's own DSP mapping.
SYNTH_PARTITION_DIRECTIVE ?= Default
# Timing-driven partition synthesis (clocked at FREQUENCY) costs the socket of a
# 4-core NT=16 IW=4 TCU simulator 14 GB more peak (40+ vs 26 GB) for the same
# LUT count; placement and routing are timing-driven either way.
SYNTH_PARTITION_TIMING ?= 0
# The source the partitions are synthesized from: the generated design, or any
# file that defines the same modules with the same ports, such as models
# threaded by another compiler. The kernel only sees their ports.
SYNTH_PARTITION_SRC ?= $(sv_generated)
# Modules to synthesize in place of the generated design's own, outside the
# partitions (e.g. the rest of the simulator built by another compiler): each
# must have the ports of the one it replaces.
SYNTH_REPLACE_SRC ?=

SLASHKIT ?= slashkit
# Parallel Vivado runs during link. The FAME-5 images synthesize one enormous
# module whose peak memory approaches the whole machine; SLASHKIT_JOBS=1 gives
# it the machine alone.
SLASHKIT_JOBS ?= 8
VIVADO := $(XILINX_VIVADO)/bin/vivado

BITSTREAM_DIR := $(buildroot)/bitstream
BUILD_DIR     := $(BITSTREAM_DIR)/build_dir.$(TARGET)
VBIN          := $(BUILD_DIR)/$(PROJECT_NAME).vbin
LINK_CFG      := $(BITSTREAM_DIR)/link.$(TARGET).cfg
# Per TARGET: hw and sim package different flattenings of the same design, and
# a kernel shared between them is linked into whichever image builds next.
PACKAGED      := $(BUILD_DIR)/packaged_kernel/component.xml
# Models synthesized apart from the kernel (SYNTH_PARTITIONS), and the hook
# that links them into it.
PARTITION_DIR   := $(BUILD_DIR)/partitions
PARTITION_FILL  := $(PARTITION_DIR)/fill.tcl
PARTITION_STAMP := $(BITSTREAM_DIR)/partitions.$(TARGET).txt

# The design under test is packaged with the simulator, so the kernel is stale
# whenever it changes: its flattened source list is rewritten on every
# regeneration, and the vendor cores leave a stamp per part.
# Which design is packaged: switching to another configuration's existing,
# older source list must repackage too, which its timestamp alone never does.
VORTEX_STAMP  := $(BITSTREAM_DIR)/vortex_srcs.$(TARGET).txt
VORTEX_DEPS   := $(VORTEX_VIVADO_SRCS) $(wildcard $(VORTEX_XILINX_IP_DIR)/.part-*) $(VORTEX_STAMP)

# See the Vitis platform: the generated SV pulls its macro definitions in by an
# `include rather than through the tool's define mechanism.
sv_generated = design/FireSim-generated.sv
sv_processed = design/FireSim-generated.post-processed.sv
sv_defines   = design/defines.vh

# Rewritten only when the partition list changes, so the kernel repackages then.
$(PARTITION_STAMP): FORCE
	@mkdir -p $(@D)
	@echo "$(SYNTH_PARTITIONS)" | cmp -s - $@ || echo "$(SYNTH_PARTITIONS)" > $@

$(VORTEX_STAMP): FORCE
	@mkdir -p $(@D)
	@echo "$(VORTEX_VIVADO_SRCS) $(VORTEX_XILINX_IP_DIR)" | cmp -s - $@ || echo "$(VORTEX_VIVADO_SRCS) $(VORTEX_XILINX_IP_DIR)" > $@

# Partitions are black boxes to the kernel's synthesis.
$(sv_processed): $(sv_generated) $(PARTITION_STAMP) $(SYNTH_REPLACE_SRC)
	echo "\`include \"$(notdir $(sv_defines))\"" > $@
ifneq ($(strip $(SYNTH_PARTITIONS)),)
	python3 scripts/partition_synth.py --src $< --mark $(if $(SYNTH_REPLACE_SRC),--replace $(abspath $(SYNTH_REPLACE_SRC))) \
		$(foreach m,$(SYNTH_PARTITIONS),'$(m)') >> $@
else
	cat $< >> $@
endif

$(PARTITION_FILL): $(SYNTH_PARTITION_SRC) $(PARTITION_STAMP) scripts/partition_synth.py scripts/synth_partition.tcl
	python3 scripts/partition_synth.py --src $(abspath $(SYNTH_PARTITION_SRC)) --out $(PARTITION_DIR) \
		--part $(DEVICE) --budget $(SYNTH_MEM_BUDGET) --jobs $(SYNTH_PARTITION_JOBS) --threads $(SYNTH_PARTITION_THREADS) \
		--period $(if $(filter 1,$(SYNTH_PARTITION_TIMING)),$$(awk 'BEGIN { printf "%.3f", 1000 / $(FREQUENCY) }'),0) \
		--directive $(SYNTH_PARTITION_DIRECTIVE) --vivado $(VIVADO) $(foreach m,$(SYNTH_PARTITIONS),'$(m)')

.PHONY: bitstream package FORCE
bitstream: $(VBIN)
package: $(PACKAGED)

$(PACKAGED): scripts/package_kernel.tcl $(sv_processed) $(sv_defines) $(VORTEX_DEPS)
	mkdir -p $(BUILD_DIR)
	cd $(BITSTREAM_DIR) && $(VIVADO) -mode batch -nojournal -log package_kernel.$(TARGET).log \
		-source $(buildroot)/scripts/package_kernel.tcl -tclargs $(DEVICE) $(BUILD_DIR)

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
$(VBIN): $(PACKAGED) $(LINK_CFG) scripts/attach_pre_opt.tcl scripts/pre_opt.tcl \
		$(if $(strip $(SYNTH_PARTITIONS)),$(PARTITION_FILL) scripts/fill_partitions.tcl)
	mkdir -p $(BUILD_DIR)
	$(if $(strip $(SYNTH_PARTITIONS)),FIRESIM_PARTITION_FILL=$(PARTITION_FILL)) $(SLASHKIT) link -c $(LINK_CFG) -p $(TARGET) -o $@ -k $(PACKAGED) --vivado $(VIVADO) \
		--jobs $(SLASHKIT_JOBS) \
		--pre-synth-tcls $(buildroot)/scripts/attach_pre_opt.tcl

FORCE:
