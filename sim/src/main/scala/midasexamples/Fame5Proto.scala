// See LICENSE for license details

package firesim.midasexamples

import chisel3._
import chisel3.util._
import chisel3.experimental.annotate

import org.chipsalliance.cde.config.{Config, Parameters}

import midas.targetutils._

/** FAME-5 prototypes: N copies of a small SystemVerilog core behind a shared, rotating-priority output arbiter whose
  * ready depends combinationally on every core's valid (as a Vortex memory arbiter's does).
  *
  * The core comes in two forms with identical ports:
  *   - [[MiniCoreVerilog]]: the sv2v output as an opaque BlackBox;
  *   - [[MiniCoreFirrtl]]: a placeholder BlackBox whose extmodule is replaced, before Golden Gate runs, by the Yosys
  *     FIRRTL of the same core (see the prototype's gen.sh / splice step).
  *
  * Each core sits in a [[MiniTile]], the unit annotated for multithreading.
  */
class MiniCoreIO extends Bundle {
  val clk       = Input(Clock())
  val reset     = Input(Bool())
  val in_valid  = Input(Bool())
  val in_data   = Input(UInt(32.W))
  val in_addr   = Input(UInt(4.W))
  val in_ready  = Output(Bool())
  val out_valid = Output(Bool())
  val out_data  = Output(UInt(32.W))
  val out_ready = Input(Bool())
}

abstract class MiniCoreBB(params: Map[String, chisel3.experimental.Param]) extends BlackBox(params) {
  val io = IO(new MiniCoreIO)
}

class MiniCoreVerilog(comb: Int) extends MiniCoreBB(Map("COMB" -> comb)) with HasBlackBoxPath {
  override def desiredName = "mini_core"
  addPath(sys.env("FAME5_PROTO_DIR") + "/mini_core.v")
}

class MiniCoreFirrtl(comb: Int) extends MiniCoreBB(Map()) {
  override def desiredName = s"mini_core_fir_c$comb"
}

class MiniReq extends Bundle {
  val data = UInt(32.W)
  val addr = UInt(4.W)
}

class MiniTile(fir: Boolean, comb: Int) extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new MiniReq))
    val out = Decoupled(UInt(32.W))
  })
  val core: MiniCoreBB = if (fir) Module(new MiniCoreFirrtl(comb)) else Module(new MiniCoreVerilog(comb))
  core.io.clk       := clock
  core.io.reset     := reset.asBool
  core.io.in_valid  := io.in.valid
  core.io.in_data   := io.in.bits.data
  core.io.in_addr   := io.in.bits.addr
  io.in.ready       := core.io.in_ready
  io.out.valid      := core.io.out_valid
  io.out.bits       := core.io.out_data
  core.io.out_ready := io.out.ready
}

class Fame5ProtoDUT(n: Int, fir: Boolean, comb: Int, regReady: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val sums   = Output(Vec(n, UInt(32.W)))
    val counts = Output(Vec(n, UInt(32.W)))
  })

  def lfsrNext(r: UInt): UInt = Cat(r(0) ^ r(2) ^ r(3) ^ r(5), r(15, 1))

  // comb < 0: two module classes, half the tiles with each core variant.
  val tiles = Seq.tabulate(n)(i => Module(new MiniTile(fir, if (comb < 0) i % 2 else comb)))
  tiles.foreach(t => annotate(EnableModelMultiThreadingAnnotation(t)))

  // Per-core stimulus, each from its own LFSR.
  tiles.zipWithIndex.foreach { case (t, i) =>
    val lfsr = RegInit((((0x1234 + 977 * i) & 0xffff) | 1).U(16.W))
    lfsr              := lfsrNext(lfsr)
    t.io.in.valid     := lfsr(0) | lfsr(9)
    t.io.in.bits.data := Cat(lfsr, Reverse(lfsr) ^ i.U)
    t.io.in.bits.addr := lfsr(7, 4)
  }

  // Shared sink: one output per cycle, rotating priority, stalled by its own LFSR.
  val stall  = RegInit(0xace1.U(16.W))
  stall := lfsrNext(stall)
  val rr     = RegInit(0.U(log2Ceil(n).W))
  // regReady: each tile's output enters a 2-entry queue, so the ready a tile
  // sees is registered rather than a function of every tile's valid.
  val outs   = tiles.map(t => if (regReady) Queue(t.io.out, 2) else t.io.out)
  val valids = VecInit(outs.map(_.valid))
  val order  = (0 until n).map(k => (rr +& k.U) % n.U)
  val hit    = order.map(idx => valids(idx))
  val grant  = PriorityMux(hit, order)
  val any    = valids.asUInt.orR && !stall(3)

  val sums   = RegInit(VecInit(Seq.fill(n)(0.U(32.W))))
  val counts = RegInit(VecInit(Seq.fill(n)(0.U(32.W))))
  outs.zipWithIndex.foreach { case (o, i) =>
    o.ready := any && grant === i.U
    when(o.fire) {
      sums(i)   := (sums(i) << 5) - sums(i) + o.bits
      counts(i) := counts(i) + 1.U
    }
  }
  when(any) { rr := Mux(grant === (n - 1).U, 0.U, grant + 1.U) }

  io.sums   := sums
  io.counts := counts
}

class Fame5ProtoV8C0(implicit p: Parameters)
    extends firesim.lib.testutils.PeekPokeHarness(() => new Fame5ProtoDUT(8, false, 0))
class Fame5ProtoV8C1(implicit p: Parameters)
    extends firesim.lib.testutils.PeekPokeHarness(() => new Fame5ProtoDUT(8, false, 1))
class Fame5ProtoF8C0(implicit p: Parameters)
    extends firesim.lib.testutils.PeekPokeHarness(() => new Fame5ProtoDUT(8, true, 0))
class Fame5ProtoF8C1(implicit p: Parameters)
    extends firesim.lib.testutils.PeekPokeHarness(() => new Fame5ProtoDUT(8, true, 1))
class Fame5ProtoF8C1Q(implicit p: Parameters)
    extends firesim.lib.testutils.PeekPokeHarness(() => new Fame5ProtoDUT(8, true, 1, regReady = true))
class Fame5ProtoF64C1Q(implicit p: Parameters)
    extends firesim.lib.testutils.PeekPokeHarness(() => new Fame5ProtoDUT(64, true, 1, regReady = true))
class Fame5ProtoF8Mix(implicit p: Parameters)
    extends firesim.lib.testutils.PeekPokeHarness(() => new Fame5ProtoDUT(8, true, -1))
class Fame5ProtoV64C1(implicit p: Parameters)
    extends firesim.lib.testutils.PeekPokeHarness(() => new Fame5ProtoDUT(64, false, 1))
class Fame5ProtoF64C1(implicit p: Parameters)
    extends firesim.lib.testutils.PeekPokeHarness(() => new Fame5ProtoDUT(64, true, 1))

/** The aved example config with multithreading switched off: the unthreaded reference. */
class Fame5OffAvedConfig
    extends Config(
      new Config((_, _, _) => { case midas.EnableModelMultiThreading => false }) ++
        new DefaultAvedConfig
    )

/** The whole-GPU form of the prototype: the SystemVerilog mini_gpu (sockets of cores behind a shared arbiter) is the
  * entire DUT. [[MiniGpuVerilog]] is its sv2v output as a BlackBox; [[MiniGpuFirrtl]] is a placeholder replaced by the
  * Yosys FIRRTL of the same GPU, with every core instance annotated for threading in the annotation file rather than in
  * Chisel -- the path an all-Verilog design such as Vortex has to take.
  */
class MiniGpuIO(n: Int) extends Bundle {
  val clk    = Input(Clock())
  val reset  = Input(Bool())
  val sums   = Output(UInt((n * 32).W))
  val counts = Output(UInt((n * 32).W))
}

abstract class MiniGpuBB(n: Int, params: Map[String, chisel3.experimental.Param]) extends BlackBox(params) {
  val io = IO(new MiniGpuIO(n))
}

class MiniGpuVerilog(ns: Int, cps: Int) extends MiniGpuBB(ns * cps, Map("NS" -> ns, "CPS" -> cps)) with HasBlackBoxPath {
  override def desiredName = "mini_gpu"
  addPath(sys.env("FAME5_PROTO_DIR") + "/../p5/mini_gpu.v")
}

class MiniGpuFirrtl(n: Int) extends MiniGpuBB(n, Map()) {
  override def desiredName = "mini_gpu_fir"
}

class Fame5GpuDUT(ns: Int, cps: Int, fir: Boolean) extends Module {
  val n  = ns * cps
  val io = IO(new Bundle {
    val sums   = Output(UInt((n * 32).W))
    val counts = Output(UInt((n * 32).W))
  })
  val gpu: MiniGpuBB = if (fir) Module(new MiniGpuFirrtl(n)) else Module(new MiniGpuVerilog(ns, cps))
  gpu.io.clk   := clock
  gpu.io.reset := reset.asBool
  io.sums      := gpu.io.sums
  io.counts    := gpu.io.counts
}

class Fame5GpuV8(implicit p: Parameters)
    extends firesim.lib.testutils.PeekPokeHarness(() => new Fame5GpuDUT(4, 2, false))
class Fame5GpuF8(implicit p: Parameters)
    extends firesim.lib.testutils.PeekPokeHarness(() => new Fame5GpuDUT(4, 2, true))
class Fame5GpuV64(implicit p: Parameters)
    extends firesim.lib.testutils.PeekPokeHarness(() => new Fame5GpuDUT(16, 4, false))
class Fame5GpuF64(implicit p: Parameters)
    extends firesim.lib.testutils.PeekPokeHarness(() => new Fame5GpuDUT(16, 4, true))

/** The RAM form of the prototype: every core drives Vortex's own VX_dp_ram in all of its modes. [[RamGpuVerilog]] is
  * the sv2v output with VX_dp_ram's simulation model; [[RamGpuFirrtl]] is the Yosys FIRRTL of the same GPU, with each
  * VX_dp_ram replaced by a generated FIRRTL memory.
  */
class RamGpuIO(n: Int) extends Bundle {
  val clk    = Input(Clock())
  val reset  = Input(Bool())
  val sums   = Output(UInt((n * (if (n <= 8) 256 else 32)).W))
  val counts = Output(UInt((n * 32).W))
}

abstract class RamGpuBB(n: Int, params: Map[String, chisel3.experimental.Param]) extends BlackBox(params) {
  val io = IO(new RamGpuIO(n))
}

class RamGpuVerilog(n: Int) extends RamGpuBB(n, Map("N" -> n)) with HasBlackBoxPath {
  override def desiredName = "ram_gpu"
  addPath(sys.env("FAME5_PROTO_DIR") + "/../p6/ram_gpu.v")
}

class RamGpuFirrtl(n: Int) extends RamGpuBB(n, Map()) {
  override def desiredName = "ram_gpu_fir"
}

class Fame5RamDUT(n: Int, fir: Boolean) extends Module {
  val io = IO(new Bundle {
    val sums   = Output(UInt((n * (if (n <= 8) 256 else 32)).W))
    val counts = Output(UInt((n * 32).W))
  })
  val gpu: RamGpuBB = if (fir) Module(new RamGpuFirrtl(n)) else Module(new RamGpuVerilog(n))
  gpu.io.clk   := clock
  gpu.io.reset := reset.asBool
  io.sums      := gpu.io.sums
  io.counts    := gpu.io.counts
}

class Fame5RamV8(implicit p: Parameters)  extends firesim.lib.testutils.PeekPokeHarness(() => new Fame5RamDUT(8, false))
class Fame5RamF8(implicit p: Parameters)  extends firesim.lib.testutils.PeekPokeHarness(() => new Fame5RamDUT(8, true))
class Fame5RamV64(implicit p: Parameters) extends firesim.lib.testutils.PeekPokeHarness(() => new Fame5RamDUT(64, false))
class Fame5RamF64(implicit p: Parameters) extends firesim.lib.testutils.PeekPokeHarness(() => new Fame5RamDUT(64, true))

/** The real-core form of the prototype: N copies of Vortex's VX_core_top (NW=NT=16) under pseudo-random stimulus.
  * [[VxGpuVerilog]] is the sv2v output as a BlackBox; [[VxGpuFirrtl]] is the Yosys FIRRTL of the same stub, with
  * VX_dp_ram/VX_sp_ram generated and every core annotated for threading.
  */
class VxGpuVerilog(n: Int) extends MiniGpuBB(n, Map("N" -> n)) with HasBlackBoxPath {
  override def desiredName = "vx_gpu_stub"
  addPath(sys.env("FAME5_PROTO_DIR") + "/../p7/vx_gpu_stub.v")
  addPath(sys.env("FAME5_PROTO_DIR") + "/../p7/vx_core_top_ref.v") // $sformatf names blanked for Verilator
}

class VxGpuFirrtl(n: Int) extends MiniGpuBB(n, Map()) {
  override def desiredName = "vxgpu_fir"
}

class Fame5VxDUT(n: Int, fir: Boolean) extends Module {
  val io = IO(new Bundle {
    val sums   = Output(UInt((n * 32).W))
    val counts = Output(UInt((n * 32).W))
  })
  val gpu: MiniGpuBB = if (fir) Module(new VxGpuFirrtl(n)) else Module(new VxGpuVerilog(n))
  gpu.io.clk   := clock
  gpu.io.reset := reset.asBool
  io.sums      := gpu.io.sums
  io.counts    := gpu.io.counts
}

class Fame5VxV4(implicit p: Parameters)  extends firesim.lib.testutils.PeekPokeHarness(() => new Fame5VxDUT(4, false))
class Fame5VxF4(implicit p: Parameters)  extends firesim.lib.testutils.PeekPokeHarness(() => new Fame5VxDUT(4, true))
class Fame5VxV16(implicit p: Parameters) extends firesim.lib.testutils.PeekPokeHarness(() => new Fame5VxDUT(16, false))
class Fame5VxF16(implicit p: Parameters) extends firesim.lib.testutils.PeekPokeHarness(() => new Fame5VxDUT(16, true))
