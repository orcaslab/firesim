package midas
package platform

import chisel3._
import chisel3.util._
import junctions._
import firesim.lib.nasti._
import freechips.rocketchip.amba.axi4._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy.LazyRawModuleImp

import midas.core.{HostMemBaseAddressKey, HostMemChannelKey}
import midas.widgets.CtrlNastiKey
import midas.stage.GoldenGateOutputFileAnnotation
import midas.platform.xilinx._

object AvedConstants {
  // Wider than the addresses FPGATop uses; the shell's NoC decodes 64 bits.
  val axi4MAddressBits = 64

  // Each control register occupies its own 16-byte block (see AXI4LiteStride4).
  val ctrlStrideBits = 2
}

/** Gives every control register a 16-byte AXI4-Lite block of its own.
  *
  * The AMD Alveo V80 compute shell resolves AXI4-Lite in 16-byte blocks: a block
  * answers only if all four of its words decode, and a partially decoded one
  * returns DECERR on every word. That behaviour is what a shell that widens each
  * read to 128 bits and splits it into four would produce, and FireSim's control
  * registers are not side-effect free -- LoadMem's R_DATA dequeues on read, and
  * an MCRFile read of an empty source stalls -- so neighbouring words must never
  * be touched on a register's behalf.
  *
  * External word 4k maps to control register k. The other three words of each
  * block are answered here, OKAY with zero data, and never forwarded. One read
  * and one write are held in flight; a write's address and data are accepted in
  * either order, as AXI permits.
  */
class AXI4LiteStride4(extParams: NastiParameters, intParams: NastiParameters) extends Module {
  require(extParams.addrBits == intParams.addrBits + AvedConstants.ctrlStrideBits)
  require(extParams.dataBits == intParams.dataBits && extParams.dataBits == 32)

  val io = IO(new Bundle {
    val ext = Flipped(new NastiIO(extParams))
    val int = new NastiIO(intParams)
  })

  private def isPad(addr: UInt): Bool = addr(3, 2) =/= 0.U
  private def remap(addr: UInt): UInt = Cat(addr(extParams.addrBits - 1, 4), 0.U(2.W))

  // Reads
  val rBusy  = RegInit(false.B)
  val rLocal = Reg(Bool())
  val rId    = Reg(UInt(extParams.idBits.W))
  val arPad  = isPad(io.ext.ar.bits.addr)

  io.int.ar.valid := io.ext.ar.valid && !arPad && !rBusy
  io.int.ar.bits  := NastiReadAddressChannel(intParams, io.ext.ar.bits.id, remap(io.ext.ar.bits.addr), io.ext.ar.bits.size)
  io.ext.ar.ready := !rBusy && (arPad || io.int.ar.ready)
  when(io.ext.ar.fire) {
    rBusy  := true.B
    rLocal := arPad
    rId    := io.ext.ar.bits.id
  }

  io.ext.r.valid := rBusy && (rLocal || io.int.r.valid)
  io.ext.r.bits  := Mux(
    rLocal,
    NastiReadDataChannel(extParams, rId, 0.U),
    NastiReadDataChannel(extParams, io.int.r.bits.id, io.int.r.bits.data, io.int.r.bits.last, io.int.r.bits.resp),
  )
  io.int.r.ready := rBusy && !rLocal && io.ext.r.ready
  when(io.ext.r.fire) { rBusy := false.B }

  // Writes
  val awHave = RegInit(false.B)
  val wHave  = RegInit(false.B)
  val wBusy  = RegInit(false.B)
  val awSent = Reg(Bool())
  val wSent  = Reg(Bool())
  val wLocal = Reg(Bool())
  val awAddr = Reg(UInt(extParams.addrBits.W))
  val awId   = Reg(UInt(extParams.idBits.W))
  val awSize = Reg(UInt(3.W))
  val wData  = Reg(UInt(extParams.dataBits.W))
  val wStrb  = Reg(UInt((extParams.dataBits / 8).W))

  io.ext.aw.ready := !awHave
  io.ext.w.ready  := !wHave
  when(io.ext.aw.fire) {
    awHave := true.B
    awAddr := io.ext.aw.bits.addr
    awId   := io.ext.aw.bits.id
    awSize := io.ext.aw.bits.size
  }
  when(io.ext.w.fire) {
    wHave := true.B
    wData := io.ext.w.bits.data
    wStrb := io.ext.w.bits.strb
  }
  when(awHave && wHave && !wBusy) {
    val pad = isPad(awAddr)
    wBusy  := true.B
    wLocal := pad
    awSent := pad
    wSent  := pad
  }

  io.int.aw.valid := wBusy && !awSent
  io.int.aw.bits  := NastiWriteAddressChannel(intParams, awId, remap(awAddr), awSize)
  io.int.w.valid  := wBusy && !wSent
  io.int.w.bits   := NastiWriteDataChannel(intParams, wData, Some(wStrb))
  when(io.int.aw.fire) { awSent := true.B }
  when(io.int.w.fire) { wSent := true.B }

  val wIssued = wBusy && awSent && wSent
  io.ext.b.valid := wIssued && (wLocal || io.int.b.valid)
  io.ext.b.bits  := NastiWriteResponseChannel(extParams, awId, Mux(wLocal, 0.U, io.int.b.bits.resp))
  io.int.b.ready := wIssued && !wLocal && io.ext.b.ready
  when(io.ext.b.fire) {
    wBusy  := false.B
    awHave := false.B
    wHave  := false.B
  }
}

/** Platform shim for the AMD Alveo V80 through the SLASH compute shell.
  *
  * Unlike VitisShim there is no MMCM and no clock crossing: the shell programs
  * the kernel clock (ap_clk) to the frequency the link recorded, and the
  * simulator runs on it directly. The memory port carries the `m_axi_` prefix the
  * V80 packaging keys its clock association and burst parameters on.
  */
class AvedShim(implicit p: Parameters) extends PlatformShim {
  val intCtrlParams = p(CtrlNastiKey)
  val extCtrlParams = intCtrlParams.copy(addrBits = intCtrlParams.addrBits + AvedConstants.ctrlStrideBits)
  val extCtrlAXI4BundleParams =
    AXI4BundleParameters(extCtrlParams.addrBits, extCtrlParams.dataBits, extCtrlParams.idBits)

  val hostMemAXI4BundleParams = p(HostMemChannelKey).axi4BundleParams
    .copy(addrBits = AvedConstants.axi4MAddressBits)

  lazy val module = new LazyRawModuleImp(this) {
    override def provideImplicitClockToLazyChildren = true

    val ap_rst_n       = IO(Input(AsyncReset()))
    val ap_clk         = IO(Input(Clock()))
    val s_axi_control  = IO(Flipped(new XilinxAXI4Bundle(extCtrlAXI4BundleParams, isAXI4Lite = true)))
    val m_axi_host_mem = IO(new XilinxAXI4Bundle(hostMemAXI4BundleParams))

    val hostClock     = ap_clk
    val hostSyncReset = ResetSynchronizer(!ap_rst_n.asBool, hostClock, initValue = true)
    top.module.reset := hostSyncReset
    top.module.clock := hostClock

    top.module.mem.foreach({ case bundle =>
      bundle.ar.ready := false.B
      bundle.aw.ready := false.B
      bundle.w.ready  := false.B
      bundle.r.valid  := false.B
      bundle.b.valid  := false.B
    })

    // Control: Xilinx AXI4-Lite -> standard AXI4 -> Nasti, then the stride remap.
    val ctrlAXI4    = Wire(new AXI4Bundle(extCtrlAXI4BundleParams))
    s_axi_control.driveStandardAXI4(ctrlAXI4, hostClock, hostSyncReset)
    val axi4ToNasti = Module(new AXI42NastiIdentityModule(extCtrlAXI4BundleParams))
    axi4ToNasti.io.axi4 <> ctrlAXI4
    val stride      = withClockAndReset(hostClock, hostSyncReset) {
      Module(new AXI4LiteStride4(CreateNastiParameters(extCtrlAXI4BundleParams), intCtrlParams))
    }
    stride.io.ext <> axi4ToNasti.io.nasti
    top.module.ctrl <> stride.io.int

    if (top.module.mem.nonEmpty) {
      val addrWidth   = AvedConstants.axi4MAddressBits.W
      // The port drives absolute device addresses: the base of the memory the
      // linker binds it to.
      val hostMemBase = p(HostMemBaseAddressKey).U(addrWidth)
      m_axi_host_mem.drivenByStandardAXI4(top.module.mem(0), hostClock, hostSyncReset)
      m_axi_host_mem.araddr := hostMemBase + top.module.mem(0).ar.bits.addr
      m_axi_host_mem.awaddr := hostMemBase + top.module.mem(0).aw.bits.addr
      m_axi_host_mem.arcache.foreach { _ := AXI4Parameters.CACHE_MODIFIABLE }
      m_axi_host_mem.awcache.foreach { _ := AXI4Parameters.CACHE_MODIFIABLE }
    } else {
      m_axi_host_mem.tieoffAsManager
    }

    GoldenGateOutputFileAnnotation.annotateFromChisel(
      "// Aved Shim requires no dynamically generated macros \n",
      fileSuffix = ".defines.vh",
    )
    GoldenGateOutputFileAnnotation.annotateFromChisel("# Currently unused", ".env.tcl")
  }
}
