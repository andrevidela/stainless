/* Copyright 2009-2018 EPFL, Lausanne */

package stainless
package transformers

trait SimplifierWithPC extends TransformerWithPC with inox.transformers.SimplifierWithPC {
  import trees._
  import symbols._
  import exprOps.replaceFromSymbols

  def pp = implicitly[PathProvider[CNFPath]]

  override protected def simplify(e: Expr, path: CNFPath): (Expr, Boolean) = e match {
    case Assert(pred, oerr, body) => simplify(pred, path) match {
      case (BooleanLiteral(true), true) => simplify(body, path)
      case (BooleanLiteral(false), true) =>
        val (rb, _) = simplify(body, path)
        (Assert(BooleanLiteral(false).copiedFrom(e), oerr, rb).copiedFrom(e), opts.assumeChecked)
      case (rp, _) =>
        val (rb, _) = simplify(body, path withCond rp)
        (Assert(rp, oerr, rb).copiedFrom(e), opts.assumeChecked)
    }

    case MatchExpr(scrut, cases) =>
      val (rs, ps) = simplify(scrut, path)
      val (_, stop, purity, newCases) = cases.foldLeft((path, false, ps, Seq[MatchCase]())) {
        case (p @ (_, true, _, _), _) => p
        case ((soFar, _, purity, newCases), MatchCase(pattern, guard, rhs)) =>
          simplify(conditionForPattern[Path](rs, pattern, includeBinders = false).fullClause, soFar) match {
            case (BooleanLiteral(false), true) => (soFar, false, purity, newCases)
            case (rc, pc) =>
              val path = conditionForPattern[CNFPath](rs, pattern, includeBinders = true)
              val (rg, pg) = guard.map(simplify(_, soFar merge path)).getOrElse((BooleanLiteral(true), true))
              (and(rc, rg), pc && pg) match {
                case (BooleanLiteral(false), true) => (soFar, false, purity, newCases)
                case (BooleanLiteral(true), true) =>
                  // We know path withCond rg is true here but we need the binders
                  val bindings = conditionForPattern[Path](rs, pattern, includeBinders = true).bindings
                  val (rr, pr) = simplify(bindings.foldRight(rhs) { case ((i, e), b) => Let(i, e, b) }, soFar)
                  (soFar, true, purity && pr, newCases :+ MatchCase(WildcardPattern(None).copiedFrom(pattern), None, rr))

                case (_, _) =>
                  val (rr, pr) = simplify(rhs, soFar merge (path withCond rg))
                  val newGuard = if (rg == BooleanLiteral(true)) None else Some(rg)
                  (
                    soFar merge (path withCond rg).negate,
                    false,
                    purity && pc && pg && pr,
                    newCases :+ MatchCase(pattern, newGuard, rr)
                  )
              }
          }
      }

      newCases match {
        case Seq() => (
          Assert(
            BooleanLiteral(false).copiedFrom(e),
            Some("No valid case"),
            Choose(
              ValDef.fresh("res", e.getType).copiedFrom(e),
              BooleanLiteral(true).copiedFrom(e)
            ).copiedFrom(e)
          ).copiedFrom(e),
          opts.assumeChecked
        )

        case Seq(MatchCase(WildcardPattern(None), None, rhs)) if stop => (rhs, purity)
        case _ => (MatchExpr(rs, newCases).copiedFrom(e), purity)
      }

    case _ => super.simplify(e, path)
  }
}
