// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import firrtl.ir._
import firrtl.Utils.BoolType
import firrtl.annotations.Annotation
import firrtl.annotations.TargetToken.{Instance, OfModule}

import collection.mutable

/** Threaded channel fabric (FAME5_FABRIC=1).
  *
  * After multithreading, every channel between two threaded models is still a separate 2-token queue (a PipeChannel
  * in SimWrapper), one per instance per port: for a class of N threads with ports of total width W that is ~2*N*W
  * flip-flops plus N-way muxes, which for a folded GPU (thousands of lane instances) exceeds the device.
  *
  * Each producer thread drives at most one token per host cycle on a port, and each consumer thread consumes at most
  * one, so the channels behind one threaded port can share storage indexed by thread:
  *   - a producer set (group g, output port p) of K threads owns a 2K-entry token memory (two slots per channel,
  *     like the PipeChannel it replaces) and K two-bit enqueue counts; thread k writes its own slots;
  *   - each consumer port (group h, input port q) reads, for its thread t, the channel slot given by constant
  *     tables (which producer set, which producer thread), and advances that slot's two-bit dequeue count.
  * A channel is valid when its counts differ and ready unless it holds two tokens: the PipeChannel's latency-0,
  * 2-entry, no-flow-through queue, so the target behaviour (and the LI-BDN token semantics) are unchanged.
  *
  * Only zero-latency, non-fanout pipe channels whose two ends are both threaded are moved into the fabric; a port is
  * moved only if every one of its threads' channels qualifies. Their top-level ports and channel annotations are
  * removed, so SimWrapper no longer builds their queues. Unpipelined threading (FAME5_PIPE=1) only.
  */
object ThreadedChannelFabric {
  def enabled: Boolean = sys.env.get("FAME5_FABRIC").contains("1")

  case class Result(
    stmts:           Seq[Statement],
    handled:         Set[(Int, String)],
    removedPorts:    Set[String],
    removedChannels: Set[String],
  )

  private case class Chan(name: String, src: String, snk: String)
  private case class End(group: Int, thread: Int, port: String)

  private def u(w: Int)                       = UIntType(IntWidth(w))
  private def lit(v: BigInt, w: Int)          = UIntLiteral(v, IntWidth(w))
  private def prim(op: PrimOp, args: Expression*)          = DoPrim(op, args, Nil, UnknownType)
  private def primC(op: PrimOp, a: Expression, c: BigInt*) = DoPrim(op, Seq(a), c, UnknownType)
  private def bitsW(n: Int)                   = BigInt(n - 1).bitLength.max(1)
  private def field(e: Expression, f: String) = WSubField(e, f)

  def apply(
    groups:       Seq[(OfModule, Seq[Instance])],
    topo:         Map[OfModule, Map[String, collection.Map[Instance, WRef]]],
    moduleDefs:   Map[OfModule, Module],
    threadIdx:    Seq[Expression],
    threadedPort: (Int, String) => Expression,
    hostClock:    Expression,
    hostReset:    Expression,
    annos:        Seq[Annotation],
  )(implicit ns:  Namespace
  ): Result = {
    val fanout = annos.collect { case FAMEChannelFanoutAnnotation(names) if names.size > 1 => names }.flatten.toSet
    val chans  = annos.collect {
      case FAMEChannelConnectionAnnotation(name, PipeChannel(0), _, Some(Seq(src)), Some(Seq(snk)))
          if !fanout(name) =>
        Chan(name, src.ref, snk.ref)
    }
    val chanBySrc = chans.map(c => c.src -> c).toMap
    val chanBySnk = chans.map(c => c.snk -> c).toMap

    def portDir(cls: OfModule, p: String): Option[Direction] = moduleDefs(cls).ports.collectFirst {
      case Port(_, `p`, d, BundleType(_)) => d
    }
    def bitsType(cls: OfModule, p: String): Option[Type] = moduleDefs(cls).ports.collectFirst {
      case Port(_, `p`, _, BundleType(fs)) => fs.collectFirst { case Field("bits", _, t) => t }
    }.flatten

    // Top-level channel port -> the threaded endpoint behind it.
    val endOf = mutable.HashMap[String, End]()
    for (((cls, insts), g) <- groups.zipWithIndex; (p, conns) <- topo(cls); (inst, t) <- insts.zipWithIndex) {
      conns.get(inst).foreach(wr => endOf(wr.name) = End(g, t, p))
    }
    def topPort(g: Int, p: String, t: Int): Option[String] = {
      val (cls, insts) = groups(g)
      topo(cls).get(p).flatMap(_.get(insts(t))).map(_.name)
    }
    val portsOf = groups.indices.map(g => g -> topo(groups(g)._1).keys.toSeq).toMap
    def ground(t: Option[Type]) = t.exists {
      case _: UIntType | _: SIntType => true
      case _                         => false
    }

    // Candidate producer sets: every thread's channel exists, is plain, and ends at a threaded consumer.
    val prodSets = mutable.LinkedHashMap[(Int, String), Seq[End]]() // (g, p) -> consumer endpoint per thread
    for (g <- groups.indices; p <- portsOf(g) if portDir(groups(g)._1, p).contains(Output)) {
      val ends = groups(g)._2.indices.map(t => topPort(g, p, t).flatMap(chanBySrc.get).flatMap(c => endOf.get(c.snk)))
      if (ends.forall(_.nonEmpty) && ground(bitsType(groups(g)._1, p))) prodSets((g, p)) = ends.map(_.get)
    }
    val consPorts = mutable.LinkedHashMap[(Int, String), Seq[End]]() // (h, q) -> producer endpoint per thread
    for (h <- groups.indices; q <- portsOf(h) if portDir(groups(h)._1, q).contains(Input)) {
      val ends = groups(h)._2.indices.map(t => topPort(h, q, t).flatMap(chanBySnk.get).flatMap(c => endOf.get(c.src)))
      if (ends.forall(_.nonEmpty) && ground(bitsType(groups(h)._1, q))) consPorts((h, q)) = ends.map(_.get)
    }
    // Fixpoint: a producer set needs all its consumer ports in the fabric, and vice versa.
    var changed = true
    while (changed) {
      changed = false
      for ((k, ends) <- prodSets.toSeq if !ends.forall(e => consPorts.contains((e.group, e.port)))) {
        prodSets.remove(k); changed = true
      }
      for ((k, ends) <- consPorts.toSeq if !ends.forall(e => prodSets.contains((e.group, e.port)))) {
        consPorts.remove(k); changed = true
      }
    }
    if (prodSets.isEmpty) return Result(Nil, Set.empty, Set.empty, Set.empty)

    val stmts = mutable.ArrayBuffer[Statement]()
    val info  = FAME5Info.info

    // Storage per producer set.
    case class SetImpl(enq: WRef, deq: WRef, mem: String, kw: Int, readers: mutable.ArrayBuffer[String])
    val readersOf = consPorts.toSeq.flatMap { case ((h, q), ends) =>
      ends.map(e => (e.group, e.port)).distinct.map(s => s -> (h, q))
    }.groupBy(_._1).map { case (s, v) => s -> v.map(_._2).distinct }
    val impls     = prodSets.map { case (k @ (g, p), _) =>
      val n     = groups(g)._2.size
      val kw    = bitsW(n)
      val base  = ns.newName(s"fabric_${groups(g)._1.value}_g${g}_${p}")
      val vt    = VectorType(u(2), n)
      val zero  = ns.newName(s"${base}_zero")
      stmts += DefWire(info, zero, vt)
      stmts ++= (0 until n).map(i => Connect(info, WSubIndex(WRef(zero, vt, WireKind), i, u(2), UnknownFlow), lit(0, 2)))
      val enq   = DefRegister(info, ns.newName(s"${base}_enq"), vt, hostClock, hostReset, WRef(zero, vt, WireKind))
      val deq   = DefRegister(info, ns.newName(s"${base}_deq"), vt, hostClock, hostReset, WRef(zero, vt, WireKind))
      stmts += enq
      stmts += deq
      val rds   = readersOf.getOrElse(k, Nil).map { case (h, q) => s"r_g${h}_${q}" }
      val mem   = DefMemory(
        info,
        ns.newName(s"${base}_tokens"),
        bitsType(groups(g)._1, p).get,
        2 * n,
        1,
        0,
        rds,
        Seq("w"),
        Nil,
        ReadUnderWrite.Undefined,
      )
      stmts += mem
      k -> SetImpl(WRef(enq), WRef(deq), mem.name, kw, mutable.ArrayBuffer(rds: _*))
    }
    def memPort(m: String, port: String, f: String) = field(field(WRef(m), port), f)
    def at(v: WRef, idx: Expression)                 = WSubAccess(v, idx, u(2), UnknownFlow)

    // Producers: thread k enqueues into its own two slots.
    for (((g, p), im) <- impls) {
      val k     = threadIdx(g)
      val port  = threadedPort(g, p)
      val occ   = primC(PrimOps.Bits, prim(PrimOps.Sub, at(im.enq, k), at(im.deq, k)), 1, 0)
      val ready = DefNode(info, ns.newName(s"${im.mem}_ready"), prim(PrimOps.Neq, occ, lit(2, 2)))
      val fire  = DefNode(info, ns.newName(s"${im.mem}_fire"), prim(PrimOps.And, field(port, "valid"), WRef(ready)))
      stmts += ready
      stmts += fire
      stmts += Connect(info, field(port, "ready"), WRef(ready))
      stmts += Connect(info, memPort(im.mem, "w", "clk"), hostClock)
      stmts += Connect(info, memPort(im.mem, "w", "en"), WRef(fire))
      stmts += Connect(
        info,
        memPort(im.mem, "w", "addr"),
        prim(PrimOps.Cat, k, primC(PrimOps.Bits, at(im.enq, k), 0, 0)),
      )
      stmts += Connect(info, memPort(im.mem, "w", "data"), field(port, "bits"))
      stmts += Connect(info, memPort(im.mem, "w", "mask"), lit(1, 1))
      stmts += Conditionally(
        info,
        WRef(fire),
        Connect(info, at(im.enq, k), primC(PrimOps.Tail, prim(PrimOps.Add, at(im.enq, k), lit(1, 2)), 1)),
        EmptyStmt,
      )
    }

    // Consumers: thread t reads the slot of the producer thread that feeds it.
    for (((h, q), ends) <- consPorts) {
      val t       = threadIdx(h)
      val port    = threadedPort(h, q)
      val sets    = ends.map(e => (e.group, e.port)).distinct
      val nh      = groups(h)._2.size
      val sw      = sets.map(impls(_).kw).max
      val slotVt  = VectorType(u(sw), nh)
      val slotRom = ns.newName(s"fabric_g${h}_${q}_slot")
      stmts += DefWire(info, slotRom, slotVt)
      stmts ++= ends.zipWithIndex.map { case (e, i) =>
        Connect(info, WSubIndex(WRef(slotRom, slotVt, WireKind), i, u(sw), UnknownFlow), lit(e.thread, sw))
      }
      val slot    = DefNode(info, ns.newName(s"${slotRom}_t"), WSubAccess(WRef(slotRom, slotVt, WireKind), t, u(sw), UnknownFlow))
      stmts += slot
      val selW    = bitsW(sets.size)
      val sel: Option[Expression] = if (sets.size > 1) {
        val selVt  = VectorType(u(selW), nh)
        val selRom = ns.newName(s"fabric_g${h}_${q}_set")
        stmts += DefWire(info, selRom, selVt)
        stmts ++= ends.zipWithIndex.map { case (e, i) =>
          Connect(info, WSubIndex(WRef(selRom, selVt, WireKind), i, u(selW), UnknownFlow), lit(sets.indexOf((e.group, e.port)), selW))
        }
        val node   = DefNode(info, ns.newName(s"${selRom}_t"), WSubAccess(WRef(selRom, selVt, WireKind), t, u(selW), UnknownFlow))
        stmts += node
        Some(WRef(node))
      } else None
      val perSet  = sets.zipWithIndex.map { case (s, j) =>
        val im  = impls(s)
        val idx = DefNode(info, ns.newName(s"${slotRom}_i$j"), primC(PrimOps.Bits, WRef(slot), im.kw - 1, 0))
        val rp  = s"r_g${h}_${q}"
        stmts += idx
        stmts += Connect(info, memPort(im.mem, rp, "clk"), hostClock)
        stmts += Connect(info, memPort(im.mem, rp, "en"), lit(1, 1))
        stmts += Connect(
          info,
          memPort(im.mem, rp, "addr"),
          prim(PrimOps.Cat, WRef(idx), primC(PrimOps.Bits, at(im.deq, WRef(idx)), 0, 0)),
        )
        val v   = prim(PrimOps.Neq, at(im.enq, WRef(idx)), at(im.deq, WRef(idx)))
        (im, WRef(idx), v, memPort(im.mem, rp, "data"))
      }
      def pick(xs: Seq[Expression]): Expression = sel match {
        case None    => xs.head
        case Some(s) =>
          xs.zipWithIndex.tail.foldLeft(xs.head) { case (acc, (x, j)) =>
            Mux(prim(PrimOps.Eq, s, lit(j, selW)), x, acc, UnknownType)
          }
      }
      val valid = DefNode(info, ns.newName(s"fabric_g${h}_${q}_valid"), pick(perSet.map(_._3)))
      stmts += valid
      stmts += Connect(info, field(port, "valid"), WRef(valid))
      stmts += Connect(info, field(port, "bits"), pick(perSet.map(_._4)))
      val fire  = DefNode(info, ns.newName(s"fabric_g${h}_${q}_fire"), prim(PrimOps.And, field(port, "ready"), WRef(valid)))
      stmts += fire
      perSet.zipWithIndex.foreach { case ((im, idx, _, _), j) =>
        val cond = sel.map(s => prim(PrimOps.And, WRef(fire), prim(PrimOps.Eq, s, lit(j, selW)))).getOrElse(WRef(fire))
        stmts += Conditionally(
          info,
          cond,
          Connect(info, at(im.deq, idx), primC(PrimOps.Tail, prim(PrimOps.Add, at(im.deq, idx), lit(1, 2)), 1)),
          EmptyStmt,
        )
      }
    }

    val handled  = (prodSets.keys ++ consPorts.keys).toSet
    val moved    = for {
      ((g, p), _) <- prodSets.toSeq
      t           <- groups(g)._2.indices
      src         <- topPort(g, p, t)
      c           <- chanBySrc.get(src)
    } yield c
    println(
      s"FAME5 channel fabric: ${moved.size} channels in ${prodSets.size} producer sets, ${consPorts.size} consumer ports"
    )
    Result(stmts.toSeq, handled, moved.flatMap(c => Seq(c.src, c.snk)).toSet, moved.map(_.name).toSet)
  }
}
