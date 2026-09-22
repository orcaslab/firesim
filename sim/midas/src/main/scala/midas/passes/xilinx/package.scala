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
    * It is the standard glitch-free gate: the enable passes through a latch that is transparent only while the clock
    * is low, so it can change the output only while the clock is low, and the gated clock is put back on a global net
    * by a BUFG_FABRIC. As with BUFGCE, an enable asserted before a rising edge passes that edge.
    */
  object DefineFabricClockGate extends Transform with FunctionalPass[CircuitName] {
    val blackboxName = "FireSimFabricClockGate"
    val blackbox     = DefineAbstractClockGate.blackbox.copy(name = blackboxName, defname = blackboxName)

    val impl =
      s"""|`ifndef ${blackboxName.toUpperCase()}
          |`define ${blackboxName.toUpperCase()}
          |(* keep_hierarchy = "yes" *)
          |module ${blackboxName}(
          |  input  I,
          |  input  CE,
          |  output O
          |);
          |`ifdef SYNTHESIS
          |  wire enable;
          |  LDCE #(.INIT(1'b0), .IS_G_INVERTED(1'b1)) enable_latch (
          |    .Q(enable), .D(CE), .G(I), .GE(1'b1), .CLR(1'b0));
          |  BUFG_FABRIC gated_buffer (.I(I & enable), .O(O));
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
