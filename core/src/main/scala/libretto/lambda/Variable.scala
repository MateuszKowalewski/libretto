package libretto.lambda

import libretto.util.{Injective, Semigroup, UniqueTypeArg}

trait Variable[Var[_], VarSet]
  extends UniqueTypeArg[Var]
     with Injective[Var]
     with Semigroup[VarSet]
{
  def singleton[A](v: Var[A]): VarSet
  def union(vs: VarSet, ws: VarSet): VarSet

  override def combine(l: VarSet, r: VarSet): VarSet =
    union(l, r)
}
