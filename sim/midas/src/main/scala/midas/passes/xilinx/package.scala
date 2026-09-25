package midas.passes

import firrtl._
import firrtl.ir._
import firrtl.annotations.{CircuitName, ModuleName}
import firrtl.transforms.BlackBoxInlineAnno

package object xilinx {
  object DefineBUFGCE extends Transform with NoAnalysisPass {
    val blackboxName = "BUFGCE"
    val blackbox     = DefineAbstractClockGate.blackbox.copy(name = blackboxName, defname = blackboxName)
    val transformer = { c: Circuit => c.copy(modules = c.modules :+ blackbox) }
  }

  /** A clock gate for host regions that have no BUFGCE sites, such as the reconfigurable partition of the Alveo V80
    * compute shell, which offers only BUFG_FABRIC buffers.
    *
    * In hardware it is not a gate at all: the AND below is marked for synth_design's gated-clock conversion, which
    * moves the enable onto the clock-enable pin of every register, RAM and DSP the gated clock drives and clocks them
    * from the ungated clock. A cell advances on a rising edge exactly when the enable is high at that edge -- BUFGCE's
    * semantics -- and nothing is left on a derived clock.
    *
    * A real fabric gate cannot be made safe here. A latch-and-AND gate is only glitch-free if the clock reaches the
    * latch no later than it reaches the AND, which placement does not guarantee and Vivado does not check (it runs no
    * clock-gating check on a LUT), and its BUFG_FABRIC output arrives nanoseconds after the host clock that the rest
    * of the simulator runs on. The platform build must enable the conversion and reject any cell it leaves unconverted.
    */
  object DefineFabricClockGate extends Transform with FunctionalPass[CircuitName] {
    val blackboxName = "FireSimFabricClockGate"
    val blackbox     = DefineAbstractClockGate.blackbox.copy(name = blackboxName, defname = blackboxName)

    val impl =
      s"""|`ifndef ${blackboxName.toUpperCase()}
          |`define ${blackboxName.toUpperCase()}
          |module ${blackboxName}(
          |`ifdef SYNTHESIS
          |  (* gated_clock = "true" *) input I,
          |`else
          |  input  I,
          |`endif
          |  input  CE,
          |  output O
          |);
          |`ifdef SYNTHESIS
          |  assign O = I & CE;
          |`else
          |  // The BUFGCE simulation model: the enable is sampled at each edge.
          |  /* verilator lint_off BLKSEQ */
          |  reg gated;
          |  always @(posedge I or negedge I) begin
          |    if (CE)
          |      gated = I;
          |    else
          |      gated = 1'h0;
          |  end
          |  /* verilator lint_on BLKSEQ */
          |  assign O = gated;
          |`endif
          |endmodule
          |`endif
          |""".stripMargin

    def analyze(cs: CircuitState): CircuitName = CircuitName(cs.circuit.main)

    def transformer(cName: CircuitName) = { c: Circuit =>
      c.copy(modules = c.modules ++ Some(blackbox).filterNot(_ => c.modules.contains(blackbox)))
    }

    def annotater(cName: CircuitName) = { anns: AnnotationSeq =>
      val blackboxAnn = BlackBoxInlineAnno(ModuleName(blackboxName, cName), s"${blackboxName}.v", impl)
      anns ++ Some(blackboxAnn).filterNot(_ => anns.contains(blackboxAnn))
    }
  }

  /** Instantiates the host's clock-gate primitive wherever the target uses the abstract clock gate. */
  class ReplaceAbstractClockGates(primitive: String) extends Transform {
    def inputForm     = MidForm
    def outputForm    = MidForm
    override def name = "[Golden Gate] Xilinx ReplaceAbstractClockGates"

    def execute(state: CircuitState): CircuitState = {
      val clockModules = state.circuit.modules
        .collect({
          case m: ExtModule if m.defname == DefineAbstractClockGate.blackboxName => m.name
        })
        .toSet

      state.copy(circuit = state.circuit.mapModule(OrElseIdentity({
        case m: Module => {
          val onStmt: Function[Statement, Statement] = OrElseIdentity({
            case wi: WDefInstance if clockModules.contains(wi.module) =>
              wi.copy(module = primitive)
          })
          m.mapStmt { s => onStmt(s.mapStmt(onStmt)) }
        }
      })))
    }
  }

  /** @param fabricClockGates
    *   gate clocks in fabric (DefineFabricClockGate) rather than with BUFGCE
    */
  class HostSpecialization(fabricClockGates: Boolean) extends SeqTransform {
    val inputForm  = LowForm
    val outputForm = LowForm
    val transforms =
      if (fabricClockGates)
        Seq(DefineFabricClockGate, new ReplaceAbstractClockGates(DefineFabricClockGate.blackboxName))
      else
        Seq(DefineBUFGCE, new ReplaceAbstractClockGates(DefineBUFGCE.blackboxName))
  }
}
