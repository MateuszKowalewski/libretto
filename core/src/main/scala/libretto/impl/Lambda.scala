package libretto.impl

import libretto.BiInjective

class Lambda[-⚬[_, _], |*|[_, _]] {

  sealed trait Expr[A] {
    import Expr._

    def map[B](f: A -⚬ B): Expr[B] =
      Mapped(this, f, new Var[B]())

    def zip[B](that: Expr[B]): Expr[A |*| B] =
      Zip(this, that)
  }

  object Expr {
    sealed trait VarDefining[A] extends Expr[A] {
      def variable: Var[A] =
        this match {
          case v: Var[A] => v
          case Mapped(_, _, v) => v
          case Prj1(Split(_, v, _)) => v
          case Prj2(Split(_, _, v)) => v
        }
    }

    class Var[A]() extends VarDefining[A] {
      def testEqual[B](that: Var[B]): Option[A =:= B] =
        if (this eq that) Some(summon[A =:= A].asInstanceOf[A =:= B])
        else None
    }

    case class Mapped[A, B](a: Expr[A], f: A -⚬ B, b: Var[B]) extends VarDefining[B]

    case class Zip[A, B](a: Expr[A], b: Expr[B]) extends Expr[A |*| B]

    case class Prj1[A, B](p: Split[A, B]) extends VarDefining[A]

    case class Prj2[A, B](p: Split[A, B]) extends VarDefining[B]

    case class Split[X, Y](p: VarDefining[X |*| Y], p1: Var[X], p2: Var[Y])

    def unzip[A, B](p: Expr[A |*| B]): (Expr[A], Expr[B]) =
      p match {
        case Zip(a, b) =>
          (a, b)
        case p: VarDefining[A |*| B] =>
          val split = Split(p, new Var[A](), new Var[B]())
          (Prj1(split), Prj2(split))
      }
  }
  import Expr._

  val shuffle = new Shuffle[-⚬, |*|]
  import shuffle.~⚬

  sealed trait Vars[A] {
    def lookup[B](vb: Var[B]): Option[Vars.Contains[A, B]]

    def zip[B](that: Vars[B]): Vars[A |*| B] =
      Vars.Zip(this, that)
  }

  object Vars {
    case class Single[A](v: Var[A]) extends Vars[A] {
      override def lookup[B](w: Var[B]): Option[Contains[A, B]] =
        v.testEqual(w).map(_.substituteCo[Contains[A, *]](Contains.Id[A]()))
    }

    case class Zip[X, Y](_1: Vars[X], _2: Vars[Y]) extends Vars[X |*| Y] {
      override def lookup[B](w: Var[B]): Option[Contains[X |*| Y, B]] =
        _1.lookup(w) match {
          case Some(contains) =>
            contains match {
              case Contains.Id() => Some(Contains.Super(~⚬.Id(), _2))
              // TODO
            }
          case None =>
            _2.lookup(w) match {
              case Some(contains) =>
                contains match {
                  case Contains.Id() => Some(Contains.Super(~⚬.swap, _1))
                  // TODO
                }
              case None =>
                None
            }
        }
    }

    /** Witnesses that `Vars[A]` contains a variable `Var[B]`. */
    sealed trait Contains[A, B]
    object Contains {
      case class Id[X]() extends Contains[X, X]
      case class Super[A, B, X](f: A ~⚬ (B |*| X), remaining: Vars[X]) extends Contains[A, B]
    }
  }

  def abs[A, B](
    f: Expr[A] => Expr[B],
  )(using
    inj: BiInjective[|*|],
    ev: SymmetricSemigroupalCategory[-⚬, |*|],
  ): Either[Error, A -⚬ B] = {
    val a = new Var[A]()
    val b = f(a)
    abs(a, b).map(_.lift)
  }

  def abs[A, B](
    a: Var[A],
    b: Expr[B],
  )(using
    inj: BiInjective[|*|],
    ev: Semigroupoid[-⚬],
  ): Either[Error, A ~⚬ B] =
    abs[A, B](
      vars = Vars.Single(a),
      expr = b,
      consumed = Set.empty,
    ) match {
      case AbsRes.Full(f, _) => Right(f)
      case AbsRes.Partial(_, _, _) => Left(Error.Underused(a))
      case AbsRes.Failure(e) => Left(e)
    }

  private def abs[A, B](
    vars: Vars[A],
    expr: Expr[B],
    consumed: Set[Var[_]],
  )(using
    inj: BiInjective[|*|],
    ev: Semigroupoid[-⚬],
  ): AbsRes[A, B] = {
    def goPrj[Z, X](z: VarDefining[Z], s: Z ~⚬ (B |*| X), b: Var[B], x: Var[X]): AbsRes[A, B] =
      if (consumed.contains(z.variable)) {
        if (consumed.contains(b))
          AbsRes.Failure(Error.Overused(b))
        else
          vars.lookup(b) match {
            case None =>
              AbsRes.Failure(Error.Overused(z.variable))
            case Some(contains) =>
              contains match {
                case Vars.Contains.Id() => AbsRes.Full(~⚬.Id(), consumed + b)
                case Vars.Contains.Super(f, vars) => AbsRes.Partial(f, vars, consumed + b)
              }
          }
      } else {
        abs(vars, z, consumed) match {
          case AbsRes.Full(f, consumed) => AbsRes.Partial(f > s, Vars.Single(x), consumed + b)
          case AbsRes.Partial(f, vars, consumed) => AbsRes.Partial(f > ~⚬.fst(s) > ~⚬.assocLR, Vars.Single(x) zip vars, consumed + b)
          case AbsRes.Failure(e) => AbsRes.Failure(e)
        }
      }

    expr match {
      case v: Var[B] =>
        vars.lookup(v) match {
          case None =>
            consumed.contains(v) match {
              case true => AbsRes.Failure(Error.Overused(v))
              case false => AbsRes.Failure(Error.Undefined(v))
            }
          case Some(res) =>
            res match {
              case Vars.Contains.Id() => AbsRes.Full(~⚬.Id(), consumed + v)
              case Vars.Contains.Super(f, vars) => AbsRes.Partial(f, vars, consumed + v)
            }
        }
      case Zip(b1, b2) =>
        abs(vars, b1, consumed) match {
          case AbsRes.Partial(f, vars, consumed) =>
            abs(vars, b2, consumed) match {
              case AbsRes.Full(g, consumed) => AbsRes.Full(f > ~⚬.snd(g), consumed)
              case AbsRes.Partial(g, vars, consumed) => AbsRes.Partial(f > ~⚬.snd(g) > ~⚬.assocRL, vars, consumed)
              case AbsRes.Failure(e) => AbsRes.Failure(e)
            }
          // TODO
        }
      case Mapped(x, f, b) =>
        if (consumed.contains(b)) {
          AbsRes.Failure(Error.Overused(b))
        } else {
          vars.lookup(b) match {
            case Some(contains) =>
              contains match {
                case Vars.Contains.Id() => AbsRes.Full(~⚬.Id(), consumed + b)
                case Vars.Contains.Super(f, vars) => AbsRes.Partial(f, vars, consumed + b)
              }
            case None =>
              abs(vars, x, consumed) match {
                case AbsRes.Full(g, consumed) => AbsRes.Full(g > ~⚬.lift(f), consumed + b)
                case AbsRes.Partial(g, vars, consumed) => AbsRes.Partial(g > ~⚬.fst(~⚬.lift(f)), vars, consumed + b)
                case AbsRes.Failure(e) => AbsRes.Failure(e)
              }
          }
        }
      case Prj1(Split(bx, b, x)) =>
        goPrj(bx, ~⚬.Id(), b, x)
      case Prj2(Split(xb, x, b)) =>
        goPrj(xb, ~⚬.swap, b, x)
    }
  }

  sealed trait AbsRes[A, B]
  object AbsRes {
    case class Full[A, B](f: A ~⚬ B, consumed: Set[Var[_]]) extends AbsRes[A, B]
    case class Partial[A, Y, B](f: A ~⚬ (B |*| Y), vars: Vars[Y], consumed: Set[Var[_]]) extends AbsRes[A, B]
    case class Failure[A, B](e: Error) extends AbsRes[A, B]
  }

  sealed trait Error
  object Error {
    case class Overused(v: Var[_]) extends Error
    case class Underused(v: Var[_]) extends Error
    case class Undefined(v: Var[_]) extends Error
  }
}
