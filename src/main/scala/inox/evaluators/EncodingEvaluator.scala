/* Copyright 2009-2016 EPFL, Lausanne */

package inox
package evaluators

trait EncodingEvaluator extends DeterministicEvaluator { self =>
  import program.trees._

  protected val encoder: ast.ProgramTransformer { val sourceProgram: program.type }

  protected val underlying: DeterministicEvaluator {
    val program: encoder.targetProgram.type
  }

  lazy val options = underlying.options

  def eval(expr: Expr, model: Map[ValDef, Expr]): EvaluationResult = {
    val res = underlying.eval(
      encoder.encode(expr),
      model.map(p => encoder.encode(p._1) -> encoder.encode(p._2))
    )

    res match {
      case EvaluationResults.Successful(v) => EvaluationResults.Successful(encoder.decode(v))
      case EvaluationResults.RuntimeError(msg) => EvaluationResults.RuntimeError(msg)
      case EvaluationResults.EvaluatorError(msg) => EvaluationResults.EvaluatorError(msg)
    }
  }
}

object EncodingEvaluator {
  def solving(p: Program)
             (enc: ast.ProgramTransformer { val sourceProgram: p.type })
             (ev: DeterministicEvaluator { val program: enc.targetProgram.type }) = {
    new {
      val program: p.type = p
    } with EncodingEvaluator {
      lazy val encoder: enc.type = enc
      lazy val underlying = ev
    }
  }

}
