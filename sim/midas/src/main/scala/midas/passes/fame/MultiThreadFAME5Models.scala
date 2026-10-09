// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.traversals.Foreachers._
import firrtl.annotations.{CircuitName, ModuleName}
import firrtl.annotations.TargetToken.{Instance, OfModule}
import firrtl.Utils.BoolType
import firrtl.passes.InlineAnnotation

import midas.targetutils.FirrtlEnableModelMultiThreadingAnnotation

import collection.mutable

import midas.passes._

// PREREQUISITE: Top-level simulator is already fully FAME-Transformed
// ASSUMPTION: Each channel is bulk-connected to EXACTLY ONE top-level port
// ASSUMPTION: The direction of a channel port on a model mirrors its data flow direction

trait ReadyValidSignal {
  val ref: Expression
  def ready: WSubField = WSubField(ref, "ready", BoolType, UnknownFlow)
  def valid: WSubField = WSubField(ref, "valid", BoolType, UnknownFlow)
  def bits: WSubField  = WSubField(ref, "bits", UnknownType, UnknownFlow)
}

case class ReadyValidSink(ref: Expression) extends ReadyValidSignal

case class ReadyValidSource(ref: Expression) extends ReadyValidSignal

object FAME5Info {
  def info = FileInfo(StringLit("@ [Added during FAME5Transform]"))
}

object Counter {
  def apply(nVals: Integer, hostClock: Expression, hostReset: Expression, free: Boolean = false)(implicit
    ns: Namespace
  ): SignalInfo = {
    val maxLit = UIntLiteral(BigInt(nVals - 1))
    val name   = ns.newName("threadIdx")
    // free: run from configuration like the threaded modules' own counters,
    // so the two never change their offset at host-reset release.
    val decl   =
      if (free) DefRegister(FAME5Info.info, name, maxLit.tpe, hostClock, UIntLiteral(0), WRef(name, maxLit.tpe, RegKind))
      else DefRegister(FAME5Info.info, name, maxLit.tpe, hostClock, hostReset, UIntLiteral(0))
    val ref    = WRef(decl)
    val inc    = DoPrim(PrimOps.Add, Seq(ref, UIntLiteral(1)), Nil, UnknownType)
    val wrap   = DoPrim(PrimOps.Eq, Seq(ref, maxLit), Nil, BoolType)
    val assign = Connect(FAME5Info.info, ref, Mux(wrap, UIntLiteral(0), inc, UnknownType))
    SignalInfo(decl, assign, ref)
  }
}

/** Pipelined multithreading (FAME5_PIPE = P > 1, prototype): a thread's state
  * is read at host cycle c and written at c+P-1. Its input tokens are sampled
  * with the live counter and delayed P-1 cycles at the top; everything decided
  * at the write stage (input ready, output valid/bits, output ready) is routed
  * with the counter delayed by P-1.
  */
object Fame5Pipe {
  def depth: Int = sys.env.getOrElse("FAME5_PIPE", "1").toInt
  def delay(e: Expression, tpe: Type, n: Int, clock: Expression, reset: Option[Expression], base: String)(implicit
    ns: Namespace
  ): (Seq[Statement], Expression) = {
    (0 until n).foldLeft((Seq.empty[Statement], e)) { case ((stmts, prev), _) =>
      val name = ns.newName(base)
      val r    = reset match {
        case Some(rst) => DefRegister(FAME5Info.info, name, tpe, clock, rst, UIntLiteral(0))
        case None      => DefRegister(FAME5Info.info, name, tpe, clock, UIntLiteral(0), WRef(name, tpe, RegKind))
      }
      (stmts :+ r :+ Connect(FAME5Info.info, WRef(name, tpe, RegKind), prev), WRef(name, tpe, RegKind))
    } match {
      // The delayed value is referenced as a wire so a threaded module's
      // register-to-memory rewrite never mistakes these host registers for
      // target state.
      case (st, WRef(n, t, RegKind, f)) => (st, WRef(n, t, WireKind, f))
      case other                        => other
    }
  }
}

class StaticArbiter(counter: SignalInfo, late: Option[Expression] = None) {
  // Index used for everything decided at the write stage of a pipelined thread.
  private def lateRef: Expression = late.getOrElse(counter.ref)
  var prologue: Seq[Statement] = Nil
  private def muxIdx(select: Expression, signals: Seq[Expression]): Expression = {
    signals.zipWithIndex.tail.foldLeft(signals.head) { case (fval, (tval, idx)) =>
      Mux(DoPrim(PrimOps.Eq, Seq(select, UIntLiteral(idx)), Nil, BoolType), tval, fval, UnknownType)
    }
  }

  private def counterMask(counter: SignalInfo, idx: Integer, signal: WSubField): DoPrim = {
    val eq = DoPrim(PrimOps.Eq, Seq(counter.ref, UIntLiteral(BigInt(idx))), Nil, BoolType)
    DoPrim(PrimOps.And, Seq(signal, eq), Nil, BoolType)
  }

  private def lateMask(idx: Integer, signal: WSubField): DoPrim = {
    val eq = DoPrim(PrimOps.Eq, Seq(lateRef, UIntLiteral(BigInt(idx))), Nil, BoolType)
    DoPrim(PrimOps.And, Seq(signal, eq), Nil, BoolType)
  }

  def mux(
    sink:     ReadyValidSink,
    sources:  Seq[ReadyValidSource],
    pipe:     Option[(Int, Type, Expression, Expression, Namespace)] = None,
  ): Statement = {
    val valid0 = muxIdx(counter.ref, sources.map(_.valid))
    val bits0  = muxIdx(counter.ref, sources.map(_.bits))
    val (delayStmts, valid, bits) = pipe match {
      case Some((n, bitsTpe, clk, rst, ns)) if n > 0 =>
        val (vs, v) = Fame5Pipe.delay(valid0, BoolType, n, clk, None, "fame5_in_valid_d")(ns)
        val (bs, b) = Fame5Pipe.delay(bits0, bitsTpe, n, clk, None, "fame5_in_bits_d")(ns)
        (vs ++ bs, v, b)
      case _                                         => (Nil, valid0, bits0)
    }
    val validConn  = Connect(FAME5Info.info, sink.valid, valid)
    val bitsConn   = Connect(FAME5Info.info, sink.bits, bits)
    val readyConns = sources.zipWithIndex.map { case (source, idx) =>
      Connect(FAME5Info.info, source.ready, lateMask(idx, sink.ready))
    }
    Block(delayStmts ++: validConn +: bitsConn +: readyConns)
  }

  def demux(sinks: Seq[ReadyValidSink], source: ReadyValidSource): Statement = {
    val ready      = muxIdx(lateRef, sinks.map(_.ready))
    val readyConn  = Connect(FAME5Info.info, source.ready, ready)
    val bitsConns  = sinks.map(sink => Connect(FAME5Info.info, sink.bits, source.bits))
    val validConns = sinks.zipWithIndex.map { case (sink, idx) =>
      Connect(FAME5Info.info, sink.valid, lateMask(idx, source.valid))
    }
    Block(readyConn +: bitsConns ++: validConns)
  }
}

object MultiThreadFAME5Models extends Transform {
  def inputForm  = HighForm
  def outputForm = HighForm

  // (MTModule -> (portName -> (Instances -> Connection)))
  type TopoMap = Map[OfModule, Map[String, mutable.Map[Instance, WRef]]]

  private def analyzeAndPruneTopo(fame5InstMap: Map[Instance, OfModule], topo: TopoMap)(stmt: Statement): Statement = {
    def processChannelConn(inst: Instance, modelPort: String, topConn: WRef) = {
      if (fame5InstMap.contains(inst)) {
        topo(fame5InstMap(inst))(modelPort)(inst) = topConn
        EmptyStmt
      } else {
        stmt
      }
    }

    stmt match {
      case Connect(_, WSubField(WRef(iName, _, InstanceKind, _), ipName, BundleType(_), _), wr: WRef) =>
        // Could infer that this is an input channel, but that would be an extra assumption
        processChannelConn(Instance(iName), ipName, wr)
      case Connect(_, wr: WRef, WSubField(WRef(iName, _, InstanceKind, _), ipName, BundleType(_), _)) =>
        // Could infer that this is an output channel, but that would be an extra assumption
        processChannelConn(Instance(iName), ipName, wr)
      case Connect(_, WSubField(WRef(iName, _, InstanceKind, _), _, _, _), _)
          if fame5InstMap.contains(Instance(iName)) =>
        EmptyStmt // prune non-channel connections
      case WDefInstance(_, iName, _, _) if fame5InstMap.contains(Instance(iName))                     =>
        EmptyStmt
      case s                                                                                          =>
        s.map(analyzeAndPruneTopo(fame5InstMap, topo))
    }
  }

  private def findFAME5(modInsts: collection.Map[OfModule, mutable.LinkedHashSet[Instance]])(stmt: Statement): Unit = {
    stmt match {
      case WDefInstance(_, iName, mName, _) =>
        println(s"Found FAME5 instance ${iName} ${mName}")
        modInsts.get(OfModule(mName)).foreach(iSet => iSet += Instance(iName))
      case s                                => s.foreach(findFAME5(modInsts))
    }
  }

  override def execute(state: CircuitState): CircuitState = {
    val p = state.annotations.collectFirst({ case midas.stage.phases.ConfigParametersAnnotation(p) => p }).get
    if (p(midas.EnableModelMultiThreading)) {
      doTransform(state)
    } else {
      state
    }
  }

  private def doTransform(state: CircuitState): CircuitState = {
    val moduleDefs = state.circuit.modules.collect({ case m: Module => OfModule(m.name) -> m }).toMap

    val top         = moduleDefs(OfModule(state.circuit.main))
    implicit val ns = Namespace(top)

    val hostClock = WRef(top.ports.find(_.name == WrapTop.hostClockName).get)
    val hostReset = WRef(top.ports.find(_.name == WrapTop.hostResetName).get)

    // Populate keys from annotations, values from traversing statements
    val fame5RawInstances = new mutable.LinkedHashMap[OfModule, mutable.LinkedHashSet[Instance]]
    state.annotations.foreach {
      case FirrtlEnableModelMultiThreadingAnnotation(it) =>
        // TODO: why not use instance name from here?
        fame5RawInstances(OfModule(it.ofModule)) = new mutable.LinkedHashSet[Instance]
      case _                                             =>
    }

    top.body.foreach(findFAME5(fame5RawInstances))

    // filter models with one instance to avoid needless multithreading
    val fame5InstancesByModule = fame5RawInstances.filter { case (_, v) => v.size > 1 }
    val fame5ModulesByInstance = fame5InstancesByModule.flatMap({ case (k, v) => v.map(vv => vv -> k) }).toMap

    // Maps from an (OfModule, PortName) pair
    // It's actually nested (rather than indexed by tuple) for convenience
    // We don't track the direction in this map, since we can just find it later
    val fame5Topo: TopoMap = fame5InstancesByModule
      .map({ case (m, _) =>
        m -> moduleDefs(m).ports
          .collect({ case Port(_, name, _, BundleType(_)) =>
            name -> new mutable.HashMap[Instance, WRef]
          })
          .toMap
      })
      .toMap

    val prunedTopoTopBody = top.body.map(analyzeAndPruneTopo(fame5ModulesByInstance, fame5Topo))

    // Multi-class threading: every annotated module class is threaded by its
    // own instance count, with its own counter and arbiter. Modules below a
    // class are copied per class, since one module may sit under classes with
    // different thread counts.
    val circuitNS  = Namespace(state.circuit)
    // The clock gate FAME-1 adds to each model is combinational, so one shared
    // copy is correct; any other black box would hold state for every thread.
    // FameRam black boxes are threadable memory primitives: each class gets a
    // copy with NTHREADS/TIDXW set and thread-index/host ports added.
    val threadableExt = state.circuit.modules.collect { case e: ExtModule if e.defname == "FameRam" => e.name -> e }.toMap
    val extModules = state.circuit.modules.collect {
      case e: ExtModule if !e.defname.contains("ClockGate") && e.defname != "FameRam" => e.name
    }.toSet
    def children(mn: String): Seq[String] = {
      val acc = mutable.ArrayBuffer[String]()
      def walk(s: Statement): Unit = s match {
        case WDefInstance(_, _, cm, _) => acc += cm
        case other                     => other.foreach(walk)
      }
      moduleDefs.get(OfModule(mn)).foreach(m => walk(m.body))
      acc.toSeq
    }
    // Only modules are copied per class; black-box instances keep their module.
    def subtree(mn: String): Seq[String] =
      (mn +: children(mn).filter(c => moduleDefs.contains(OfModule(c))).flatMap(subtree)).distinct
    def subtreeExt(mn: String): Seq[String] =
      (children(mn).filterNot(c => moduleDefs.contains(OfModule(c))) ++
        children(mn).filter(c => moduleDefs.contains(OfModule(c))).flatMap(subtreeExt)).distinct
    val blackboxInClass = fame5InstancesByModule.keys.flatMap(m => subtreeExt(m.value).filter(extModules))
    if (blackboxInClass.nonEmpty)
      throw new IllegalStateException(
        s"FAME-5: black boxes inside a threaded class would be shared by every thread: ${blackboxInClass.mkString(", ")}"
      )
    // Fold factor: FAME5_FOLD="<module>=<r>,..." splits a class's instances
    // into r balanced groups, each threaded by its own physical copy with its
    // own counter and arbiter (r copies of N/r threads instead of one of N).
    // Groups of one instance are not allowed: r is capped at N/2.
    val foldOf: Map[String, Int] = sys.env
      .getOrElse("FAME5_FOLD", "")
      .split(",")
      .map(_.trim)
      .filter(_.nonEmpty)
      .map { kv =>
        val Array(k, v) = kv.split("=")
        k.trim -> v.trim.toInt
      }
      .toMap
    case class Group(cls: OfModule, idx: Int, insts: Seq[Instance]) { def size = insts.size }
    val groups: Seq[Group] = fame5InstancesByModule.toSeq.flatMap { case (m, insts) =>
      val n     = insts.size
      // A key may also name a base module: it then covers every parameterization <key>__p<n>.
      val fold  = foldOf.get(m.value).orElse(foldOf.collectFirst { case (k, v) if m.value.startsWith(k + "__p") => v })
      val r     = fold.getOrElse(1).max(1).min(n / 2).max(1)
      val sizes = (0 until r).map(g => n / r + (if (g < n % r) 1 else 0))
      val starts = sizes.scanLeft(0)(_ + _)
      sizes.indices.map(g => Group(m, g, insts.toSeq.slice(starts(g), starts(g) + sizes(g))))
    }
    val groupsOf = groups.groupBy(_.cls)
    // One threaded module definition per (class, group size).
    val variants = groups.map(g => (g.cls, g.size)).distinct
    val single     = variants.size <= 1
    val classNames = variants.map { case (m, sz) =>
      val multiSize = variants.count(_._1 == m) > 1
      (m, sz) -> subtree(m.value).map { x =>
        val base = if (single) s"${x}_threaded" else s"${x}_threaded_${m.value}"
        x -> circuitNS.newName(if (multiSize) s"${base}_n${sz}" else base)
      }.toMap
    }.toMap
    // Threadable black boxes below each class, renamed per class variant.
    val classExtNames = variants.map { case (m, sz) =>
      val multiSize = variants.count(_._1 == m) > 1
      (m, sz) -> subtreeExt(m.value).filter(threadableExt.contains).map { x =>
        val base = if (single) s"${x}_threaded" else s"${x}_threaded_${m.value}"
        x -> circuitNS.newName(if (multiSize) s"${base}_n${sz}" else base)
      }.toMap
    }.toMap
    val threadableCopies = variants.flatMap { case key @ (_, n) =>
      val tw = BigInt(n - 1).bitLength.max(1)
      classExtNames(key).map { case (orig, newName) =>
        val e = threadableExt(orig)
        e.copy(
          name   = newName,
          params = e.params.filterNot(p => p.name == "NTHREADS" || p.name == "TIDXW") ++
            Seq(IntParam("NTHREADS", n), IntParam("TIDXW", tw)),
        )
      }
    }
    MuxingMultiThreader.threadableNames = threadableCopies.map(_.name).toSet
    val nThreads = groups.map(_.size).sorted.lastOption.getOrElse(1)

    val threadedInstances = groups.map { g =>
      val nm = if (groupsOf(g.cls).size > 1) s"${g.cls.value}_threaded_g${g.idx}" else s"${g.cls.value}_threaded"
      g -> WDefInstance(FAME5Info.info, ns.newName(nm), classNames((g.cls, g.size))(g.cls.value), UnknownType)
    }.toMap

    val threadCounters = groups.map { g =>
      g -> Counter(g.size, hostClock, hostReset, free = Fame5Pipe.depth > 1)
    }.toMap

    println(s"FAME5 multithreading ${nThreads} nThreads")
    groupsOf.foreach { case (m, gs) =>
      println(s"FAME5 class ${m.value}: ${gs.map(_.size).sum} threads on ${gs.size} cop${if (gs.size > 1) "ies" else "y"} (${gs.map(_.size).mkString("+")})")
    }
    // - Add (de)muxes in front of each group's multi-threaded model
    //    ---------                 -----------
    //  ->| Mod 0 |                 |         |
    //    ---------      ->|-----|  |         |
    //  ->| Mod 1 |  =>  ->| Mux |->| MTModel |
    //    ---------      ->|-----|  |         |
    //  ->| Mod 2 |                 |         |
    //    ---------                 -----------
    val pipeDepth  = Fame5Pipe.depth
    val lateCounters = threadCounters.map { case (g, c) =>
      val tpe          = UIntLiteral(BigInt(g.size - 1)).tpe
      // A pure delay, like the threaded module's own late index: a reset here
      // would point the first P-1 write stages after reset at thread 0.
      val (stmts, ref) = Fame5Pipe.delay(c.ref, tpe, pipeDepth - 1, hostClock, None, "threadIdx_late")
      g -> (stmts, ref)
    }
    if (pipeDepth > 1) println(s"FAME5 pipelined multithreading: ${pipeDepth} stages")
    // Channels between two threaded groups move into the threaded channel fabric (FAME5_FABRIC=1).
    val fabric = if (ThreadedChannelFabric.enabled && pipeDepth == 1) {
      ThreadedChannelFabric(
        groups.map(g => (g.cls, g.insts)),
        fame5Topo,
        moduleDefs,
        groups.map(g => threadCounters(g).ref),
        (gi: Int, port: String) => WSubField(WRef(threadedInstances(groups(gi))), port),
        hostClock,
        hostReset,
        state.annotations.toSeq,
      )
    } else ThreadedChannelFabric.Result(Nil, Set.empty, Set.empty, Set.empty)
    val multiThreadedConns: Seq[Statement] = groups.flatMap(g => lateCounters(g)._1) ++ groups.zipWithIndex.flatMap { case (g, gi) =>
      val mod          = g.cls
      val connsByPort  = fame5Topo(mod).filterNot { case (port, _) => fabric.handled((gi, port)) }
      val arbiter      = new StaticArbiter(threadCounters(g), if (pipeDepth > 1) Some(lateCounters(g)._2) else None)
      connsByPort.toSeq.map { case (port, connsByInstance) =>
        val canonicalOrderedConns = g.insts.map(inst => connsByInstance(inst))
        if (moduleDefs(mod).ports.exists(p => p.name == port && p.direction == Input)) {
          val sink    = ReadyValidSink(WSubField(WRef(threadedInstances(g)), port))
          val sources = canonicalOrderedConns.map(s => ReadyValidSource(s))
          val bitsTpe = moduleDefs(mod).ports.collectFirst { case Port(_, `port`, _, BundleType(fs)) =>
            fs.collectFirst { case Field("bits", _, t) => t }.get
          }.get
          arbiter.mux(sink, sources, Some((pipeDepth - 1, bitsTpe, hostClock, hostReset, ns)))
        } else {
          val sinks  = canonicalOrderedConns.map(s => ReadyValidSink(s))
          val source = ReadyValidSource(WSubField(WRef(threadedInstances(g)), port))
          arbiter.demux(sinks, source)
        }
      }
    }

    val insts      = groups.map(threadedInstances) // keep ordering
    val counters   = groups.map(threadCounters)    // keep ordering
    val clockConns =
      insts.map(i => Connect(FAME5Info.info, WSubField(WRef(i), WrapTop.hostClockName), WRef(WrapTop.hostClockName)))
    val resetConns =
      insts.map(i => Connect(FAME5Info.info, WSubField(WRef(i), WrapTop.hostResetName), WRef(WrapTop.hostResetName)))

    val prologue             = insts ++: clockConns ++: resetConns ++: counters.flatMap(c => Seq(c.decl, c.assigns))
    val multiThreadedTopBody = Block(prologue ++: prunedTopoTopBody +: (multiThreadedConns ++ fabric.stmts))

    val transformedModules = state.circuit.modules.flatMap {
      case m: Module if (m.name == state.circuit.main) =>
        Seq(m.copy(ports = m.ports.filterNot(p => fabric.removedPorts(p.name)), body = multiThreadedTopBody))
      case m: Module                                   =>
        val copies = variants.collect {
          case key @ (_, sz) if classNames(key).contains(m.name) =>
            MuxingMultiThreader(classNames(key) ++ classExtNames(key))(m, sz, pipe = pipeDepth)
        }
        m +: copies
      case m                                           => Seq(m)
    } ++ threadableCopies

    // TODO: Renames!

    val threadedCircuit        = state.circuit.copy(modules = transformedModules)
    val withMemImpls           = ImplementThreadedMems(threadedCircuit)
    val memImplNames           = withMemImpls.modules.map(_.name).toSet -- threadedCircuit.modules.map(_.name).toSet
    val inlineThreadedMemAnnos = memImplNames.map(s => InlineAnnotation(ModuleName(s, CircuitName(withMemImpls.main))))

    val keptAnnos              = state.annotations.filterNot {
      case a: FAMEChannelConnectionAnnotation => fabric.removedChannels(a.globalName)
      case _                                  => false
    }
    state.copy(circuit = withMemImpls, annotations = keptAnnos ++ inlineThreadedMemAnnos)
  }
}
