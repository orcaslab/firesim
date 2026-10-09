// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.traversals.Foreachers._
import firrtl.passes.MemPortUtils._
import firrtl.Utils.{kind, BoolType}

import collection.mutable.ArrayBuffer

// Invalidates WIR _.tpe (types of memories change)
object MuxingMultiThreader {

  type FreshNames = Map[String, Seq[String]]

  val rPortName = "read"
  val wPortName = "write"
  val tIdxName  = "threadIdx"

  def rField(mem: DefMemory, field: String): Expression = memPortField(mem, rPortName, field)
  def wField(mem: DefMemory, field: String): Expression = memPortField(mem, wPortName, field)

  def onExprRHS(expr: Expression): Expression = expr match {
    case WRef(name, tpe, RegKind, _) =>
      WSubField(WSubField(WRef(name, tpe, MemKind), rPortName), "data")
    case e                           => e.map(onExprRHS)
  }

  def regWriteAsMemWrite(info: Info, name: String, tpe: Type, rhs: Expression): Statement = {
    val infos       = FAME5Info.info ++ info
    val wPort       = WSubField(WRef(name), wPortName)
    val dataConnect = Connect(infos, WSubField(wPort, "data"), rhs)
    val enConnect   = Connect(infos, WSubField(wPort, "en"), UIntLiteral(1))
    Block(Seq(dataConnect, enConnect))
  }

  def onStmt(newResets: ArrayBuffer[Statement], nThreads: BigInt, tIdx: Expression)(stmt: Statement): Statement =
    stmt match {
      case DefRegister(info, name, tpe, clock, reset, init) =>
        val infos      = FAME5Info.info ++ info
        val mem        = DefMemory(infos, name, tpe, nThreads, 1, 0, Seq(rPortName), Seq(wPortName), Nil)
        val rClockConn = Connect(infos, rField(mem, "clk"), clock)
        val rEnConn    = Connect(infos, rField(mem, "en"), UIntLiteral(1))
        val rAddrConn  = Connect(infos, rField(mem, "addr"), tIdx)
        val wClockConn = Connect(infos, wField(mem, "clk"), clock)
        val wEnDefault = Connect(infos, wField(mem, "en"), UIntLiteral(0))
        val wMaskConn  = Connect(infos, wField(mem, "mask"), UIntLiteral(1))
        val wAddrConn  = Connect(infos, wField(mem, "addr"), tIdx)
        if (WrappedExpression(init) != WrappedExpression(WRef(name))) {
          val doReset = regWriteAsMemWrite(info, name, tpe, onExprRHS(init))
          newResets += Conditionally(infos, reset, doReset, EmptyStmt)
        }
        Block(Seq(mem, rClockConn, rEnConn, rAddrConn, wClockConn, wEnDefault, wMaskConn, wAddrConn))
      case Connect(info, WRef(name, tpe, RegKind, _), rhs)  =>
        regWriteAsMemWrite(info, name, tpe, onExprRHS(rhs))
      case Connect(_, lhs, _) if (kind(lhs) == RegKind)     =>
        throw CustomTransformException(
          new IllegalArgumentException(s"Cannot handle complex register assignment to ${lhs}")
        )
      case mem: DefMemory                                   =>
        require(mem.readLatency == 0 || mem.readLatency == 1, "Memories must have combinational or single-cycle reads")
        require(mem.writeLatency == 1, "Memories must have one-cycle write latency")
        Block(
          ThreadedMem(nThreads, mem),
          Connect(FAME5Info.info, WSubField(WRef(mem.name), WrapTop.hostClockName), WRef(WrapTop.hostClockName)),
          Connect(FAME5Info.info, WSubField(WRef(mem.name), tIdxName), lateIdx),
        )
      case s                                                => s.map(onStmt(newResets, nThreads, tIdx)).map(onExprRHS)
    }

  /** Threaded-state storage, selected by FAME5_STATE (prototype switch):
    *   mem1   - one 1-bit-wide, n-deep memory per register (the original scheme)
    *   packed - all registers sharing a clock packed into wide n-deep memories,
    *            FAME5_PACK_W bits each, so the FPGA's multi-bit LUTRAM shapes apply
    *   ff     - n copies of each register selected by the thread index
    */
  def stateMode: String = sys.env.getOrElse("FAME5_STATE", "mem1")
  // Threaded copies of FameRam black boxes: their instances get the thread index.
  var threadableNames: Set[String] = Set.empty
  private var lateIdx: Expression = WRef(tIdxName)
  def packWidth: Int    = sys.env.getOrElse("FAME5_PACK_W", "1024").toInt

  private def groundWidth(t: Type): Option[BigInt] = t match {
    case UIntType(IntWidth(w)) => Some(w)
    case SIntType(IntWidth(w)) => Some(w)
    case _                     => None
  }

  private def asU(e: Expression): Expression = e.tpe match {
    case _: SIntType => DoPrim(PrimOps.AsUInt, Seq(e), Nil, UnknownType)
    case _           => e
  }

  private def balancedCat(es: Seq[Expression]): Expression =
    if (es.size == 1) es.head
    else {
      val (hi, lo) = es.splitAt(es.size / 2)
      DoPrim(PrimOps.Cat, Seq(balancedCat(hi), balancedCat(lo)), Nil, UnknownType)
    }

  private def collectRegs(s: Statement, acc: ArrayBuffer[DefRegister]): Unit = s match {
    case r: DefRegister => acc += r
    case other          => other.foreach((x: Statement) => collectRegs(x, acc))
  }

  /** Packed or flip-flop storage for every ground-typed register; returns None in mem1 mode. */
  private var pipeDepth: Int = 1
  private var hostClk: Expression = WRef(WrapTop.hostClockName)
  private var tIdxWrite: Expression = UIntLiteral(0)
  private def transformState(module: Module, n: BigInt, tIdx: Expression, ns: Namespace): Option[Statement] = {
    val mode = stateMode
    if (pipeDepth > 1) require(mode == "packed", "FAME5_PIPE > 1 requires FAME5_STATE=packed")
    if (mode == "mem1") return None
    val regs   = ArrayBuffer[DefRegister]()
    collectRegs(module.body, regs)
    val target = regs.filter(r => groundWidth(r.tpe).exists(_ > 0))
    if (target.isEmpty) return None
    val byName = target.map(r => r.name -> r).toMap
    val next   = collection.mutable.LinkedHashMap[String, Expression]()
    val infos  = FAME5Info.info

    // Where each register's current-thread value is read from.
    val readExpr = collection.mutable.HashMap[String, Expression]()
    val decls    = ArrayBuffer[Statement]()

    if (mode == "packed") {
      val byClock = target.groupBy(r => r.clock.serialize).toSeq.sortBy(_._1)
      val chunks  = ArrayBuffer[(String, Expression, Seq[(DefRegister, BigInt)], BigInt)]()
      byClock.foreach { case (_, rs) =>
        var cur = ArrayBuffer[(DefRegister, BigInt)](); var off = BigInt(0)
        def flush(): Unit = if (cur.nonEmpty) {
          chunks += ((ns.newName("fame5_state"), cur.head._1.clock, cur.toSeq, off)); cur = ArrayBuffer(); off = 0
        }
        rs.foreach { r =>
          val w = groundWidth(r.tpe).get
          if (off > 0 && off + w > packWidth) flush()
          cur += ((r, off)); off += w
        }
        flush()
      }
      chunks.foreach { case (mName, clk, members, width) =>
        val tpe = UIntType(IntWidth(width))
        val mem = DefMemory(infos, mName, tpe, n, 1, 0, Seq(rPortName), Seq(wPortName), Nil)
        decls ++= Seq(
          mem,
          Connect(infos, rField(mem, "clk"), clk),
          Connect(infos, rField(mem, "en"), UIntLiteral(1)),
          Connect(infos, rField(mem, "addr"), tIdx),
          Connect(infos, wField(mem, "clk"), clk),
          Connect(infos, wField(mem, "en"), UIntLiteral(1)),
          Connect(infos, wField(mem, "mask"), UIntLiteral(1)),
          Connect(infos, wField(mem, "addr"), tIdxWrite),
        )
        val data0 = WSubField(WSubField(WRef(mName, UnknownType, MemKind), rPortName), "data")
        val (dly, data) =
          if (pipeDepth > 1) midas.passes.fame.Fame5Pipe.delay(data0, tpe, pipeDepth - 1, hostClk, None, s"${mName}_rd")(ns)
          else (Nil, data0)
        decls ++= dly
        members.foreach { case (r, off) =>
          val w     = groundWidth(r.tpe).get
          val slice = DoPrim(PrimOps.Bits, Seq(data), Seq(off + w - 1, off), UnknownType)
          readExpr(r.name) = r.tpe match {
            case _: SIntType => DoPrim(PrimOps.AsSInt, Seq(slice), Nil, UnknownType)
            case _           => slice
          }
        }
      }
      val build = () =>
        chunks.toSeq.map { case (mName, _, members, _) =>
          val parts = members.reverse.map { case (r, _) => asU(next.getOrElse(r.name, readExpr(r.name))) }
          Connect(infos, WSubField(WSubField(WRef(mName, UnknownType, MemKind), wPortName), "data"), balancedCat(parts))
        }
      pendingWrites = Some(build)
    } else if (mode == "ff") {
      target.foreach { r =>
        val copies = (0 until n.toInt).map(t => DefRegister(infos, ns.newName(s"${r.name}_t$t"), r.tpe, r.clock, UIntLiteral(0), WRef(s"${r.name}_t$t")))
        val fixed  = copies.map(c => c.copy(init = WRef(c.name, r.tpe, RegKind)))
        decls ++= fixed
        val sel = fixed.zipWithIndex.tail.foldLeft(WRef(fixed.head.name, r.tpe, WireKind): Expression) { case (acc, (c, t)) =>
          Mux(DoPrim(PrimOps.Eq, Seq(tIdx, UIntLiteral(t)), Nil, BoolType), WRef(c.name, r.tpe, WireKind), acc, r.tpe)
        }
        val rd  = DefNode(infos, ns.newName(s"${r.name}_rd"), sel)
        decls += rd
        readExpr(r.name) = WRef(rd.name, r.tpe, NodeKind)
        ffCopies(r.name) = fixed.map(_.name)
      }
      pendingWrites = Some(() =>
        target.toSeq.flatMap { r =>
          ffCopies(r.name).zipWithIndex.map { case (c, t) =>
            val sel = DoPrim(PrimOps.Eq, Seq(tIdx, UIntLiteral(t)), Nil, BoolType)
            val w   = groundWidth(r.tpe).get
            val nxt = DoPrim(PrimOps.Pad, Seq(next.getOrElse(r.name, readExpr(r.name))), Seq(w), UnknownType)
            Connect(infos, WRef(c, r.tpe, RegKind), Mux(sel, nxt, WRef(c, r.tpe, RegKind), UnknownType))
          }
        }
      )
    } else throw new IllegalArgumentException(s"FAME5_STATE=$mode")

    stateRead = readExpr.toMap
    stateNext = next
    stateRegs = byName
    Some(Block(decls.toSeq))
  }

  // Per-module scratch for the packed/ff rewrite (the transform runs modules one at a time).
  private var stateRead: Map[String, Expression]                         = Map.empty
  private var stateNext: collection.mutable.LinkedHashMap[String, Expression] = collection.mutable.LinkedHashMap.empty
  private var stateRegs: Map[String, DefRegister]                        = Map.empty
  private var pendingWrites: Option[() => Seq[Statement]]                = None
  private val ffCopies = collection.mutable.HashMap[String, Seq[String]]()

  private def onExprState(expr: Expression): Expression = expr match {
    case WRef(name, _, RegKind, _) if stateRead.contains(name) => stateRead(name)
    case e                                                     => e.map(onExprState)
  }

  private def onStmtState(stmt: Statement): Statement = stmt match {
    case r: DefRegister if stateRegs.contains(r.name) =>
      if (WrappedExpression(r.init) != WrappedExpression(WRef(r.name)))
        stateResets += ((r.name, r.reset, r.init))
      EmptyStmt
    case Connect(_, WRef(name, _, RegKind, _), rhs) if stateRegs.contains(name) =>
      stateNext(name) = onExprState(rhs); EmptyStmt
    case s => s.map(onStmtState).map(onExprState)
  }
  private val stateResets = ArrayBuffer[(String, Expression, Expression)]()

  def apply(
    threadedModuleNames: Map[String, String]
  )(module:              Module,
    n:                   BigInt,
    useTargetClock:      Boolean = false,
    pipe:                Int = 1,
  ): Module = {
    // Simplify all memories first
    val ns = Namespace(module)

    val hostClock = WRef(WrapTop.hostClockName)

    val clockPort      = module.ports.filter(p => p.name == "clock")
    val targetClock    = if (clockPort.size > 0) WRef(clockPort.head) else hostClock
    val threadIdxClock = if (useTargetClock) targetClock else hostClock

    val tIdxMax    = UIntLiteral(n - 1)
    val tIdxType   = UIntType(tIdxMax.width)
    val tIdxRef    = WRef(ns.newName("threadIdx"), tIdxType, RegKind)
    val tIdxDecl   = DefRegister(FAME5Info.info, tIdxRef.name, tIdxType, threadIdxClock, UIntLiteral(0), tIdxRef)
    val tIdxUpdate = Mux(
      DoPrim(PrimOps.Eq, Seq(tIdxRef, tIdxMax), Nil, BoolType),
      UIntLiteral(0),
      DoPrim(PrimOps.Add, Seq(tIdxRef, UIntLiteral(1)), Nil, BoolType),
      tIdxType,
    )
    val tIdxConn   = Connect(FAME5Info.info, tIdxRef, tIdxUpdate)

    // Packed / flip-flop state first (FAME5_STATE); registers it claims are
    // removed from the body, the rest (memories, instances) go through onStmt.
    stateRead = Map.empty; stateNext = collection.mutable.LinkedHashMap.empty; stateRegs = Map.empty
    pendingWrites = None; ffCopies.clear(); stateResets.clear()
    // The packed/ff logic names the thread index as a plain reference, so the
    // register-to-memory rewrite of onStmt below never touches it.
    val tIdxPlain  = WRef(tIdxRef.name, tIdxType, WireKind)
    pipeDepth = pipe; hostClk = hostClock
    // Late thread index: the thread whose write stage is this host cycle.
    val (lateDecls, tIdxLate) =
      if (pipe > 1) midas.passes.fame.Fame5Pipe.delay(tIdxPlain, tIdxType, pipe - 1, hostClock, None, "threadIdx_late")(ns)
      else (Nil, tIdxPlain)
    tIdxWrite = tIdxLate
    lateIdx = tIdxLate
    val stateDecls0 = transformState(module, n, tIdxPlain, ns)
    // The late index is needed by threaded memories even in a module with no
    // register state (e.g. a RAM wrapper), so it is declared whenever P > 1.
    val stateDecls =
      if (lateDecls.isEmpty) stateDecls0 else Some(Block(lateDecls ++ stateDecls0.toSeq))
    val body0      = if (stateDecls.isDefined) onStmtState(module.body) else module.body
    // Reset: the next value of a reset register is its init while reset is high.
    stateResets.foreach { case (name, rst, init) =>
      val cur = stateNext.getOrElse(name, stateRead(name))
      stateNext(name) = Mux(onExprState(rst), onExprState(init), cur, UnknownType)
    }
    val stateWrites = pendingWrites.map(f => Block(f())).getOrElse(EmptyStmt)

    // Resets transformed to conditional stores to threading RAMs
    val newResets = new ArrayBuffer[Statement]
    val threaded  = Block(stateDecls.getOrElse(EmptyStmt), onStmt(newResets, n, tIdxRef)(body0), stateWrites)

    // Uses only threaded instances
    val (iDecls, threadedImpl0) = SeparateInstanceDecls(threaded)
    val threadableIdxConns      = ArrayBuffer[Statement]()
    // The tie-offs of a threadable black box's host ports are replaced below.
    val threadableInsts = iDecls.collect {
      case i if threadedModuleNames.get(i.module).exists(threadableNames) => i.name
    }.toSet
    val hostPortNames = Set(WrapTop.hostClockName, WrapTop.hostResetName, tIdxName)
    def dropTieOffs(st: Statement): Statement = st match {
      case Connect(_, WSubField(WRef(in, _, _, _), pn, _, _), _) if threadableInsts(in) && hostPortNames(pn) => EmptyStmt
      case other => other.map(dropTieOffs)
    }
    val threadedImpl = if (threadableInsts.isEmpty) threadedImpl0 else dropTieOffs(threadedImpl0)

    // TODO: earlier in the compiler, every module should get hostClock/hostReset ports hooked up
    val threadedChildren = iDecls.map {
      case i if (threadedModuleNames.contains(i.module)) =>
        val renamed = threadedModuleNames(i.module)
        if (threadableNames(renamed))
          threadableIdxConns += Connect(FAME5Info.info, WSubField(WRef(i.name), tIdxName), lateIdx)
        AddHostClockAndReset(i.copy(module = renamed))
      case i                                             => i
    }

    // Thread-index connections of threadable black boxes come last: the (late)
    // index they use is declared inside the threaded body.
    val threadedBody = Block(
      threadedChildren ++: tIdxDecl +: tIdxConn +: threadedImpl +: (threadableIdxConns.toSeq ++ newResets.toSeq)
    )
    AddHostClockAndReset(
      Module(FAME5Info.info ++ module.info, threadedModuleNames(module.name), module.ports, threadedBody)
    )
  }
}
