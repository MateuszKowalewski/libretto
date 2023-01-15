package libretto.lambda

import libretto.util.BiInjective

class LambdasOne[-⚬[_, _], |*|[_, _], One, Var[_], VarSet](
  varSynthesizer: LambdasOne.VarSynthesizer[Var, |*|],
)(using
  inj: BiInjective[|*|],
  variables: Variable[Var, VarSet],
  smc: SymmetricMonoidalCategory[-⚬, |*|, One],
) extends Lambdas[-⚬, |*|, Var, VarSet, Lambdas.Error[VarSet], Lambdas.Error.LinearityViolation[VarSet]] {
  import varSynthesizer.newSyntheticVar

  type Error              = Lambdas.Error[VarSet]
  val  Error              = Lambdas.Error
  type LinearityViolation = Lambdas.Error.LinearityViolation[VarSet]
  val  LinearityViolation = Lambdas.Error.LinearityViolation

  val lambdas = LambdasImpl[-⚬, |*|, Var, VarSet, Error, LinearityViolation]

  override type AbstractFun[A, B] = lambdas.AbstractFun[A, B]
  override object AbstractFun extends AbstractFuns {
    export lambdas.AbstractFun.fold
  }

  sealed trait Expr[A]

  override object Expr extends Exprs {
    private[LambdasOne] case class LambdasExpr[A](value: lambdas.Expr[A]) extends Expr[A]
    private[LambdasOne] case class OneExpr[A](
      oneVar: Var[One],
      tail: OneTail[A],
    ) extends Expr[A]

    private[Expr] sealed trait OneTail[A] {
      import OneTail._

      def apply(expr: lambdas.Expr[One]): lambdas.Expr[A] =
        this match {
          case Id => expr
          case Map(init, f, v) => (init(expr) map f)(v)
          case Zip(t1, t2, v)  => (t1(expr) zip t2(expr))(v)
          case Prj1(t, v1, v2) => lambdas.Expr.unzip(t(expr))(v1, v2)._1
          case Prj2(t, v1, v2) => lambdas.Expr.unzip(t(expr))(v1, v2)._2
        }

      def apply(t: OneTail[One]): OneTail[A]

      def apply1(init: OneTail.OneTail1[One]): OneTail.OneTail1[A] =
        this match {
          case Id             => init
          case t: OneTail1[A] => t(init)
        }

      def terminalVarsOpt: Either[One =:= A, Vars[A]]
    }
    private[Expr] object OneTail {
      case object Id extends OneTail[One] {
        override def apply(t: OneTail[One]): OneTail[One] = t
        override def terminalVarsOpt = Left(summon[One =:= One])
      }
      sealed trait OneTail1[A] extends OneTail[A] {
        override def apply(t: OneTail[One]): OneTail1[A] =
          this match {
            case Map(init, f, v) => Map(init(t), f, v)
            case Zip(t1, t2, v)  => Zip(t1(t), t2(t), v)
            case Prj1(xy, x, y)  => Prj1(xy(t), x, y)
            case Prj2(xy, x, y)  => Prj2(xy(t), x, y)
          }

        def terminalVars: Vars[A] =
          this match {
            case Map(_, _, v)  => Vars.single(v)
            case Zip(_, _, v)  => Vars.single(v)
            case Prj1(_, v, _) => Vars.single(v)
            case Prj2(_, _, v) => Vars.single(v)
          }

        override def terminalVarsOpt: Either[One =:= A, Vars[A]] =
          Right(terminalVars)
      }
      case class Map[A, B](init: OneTail[A], f: A -⚬ B, resultVar: Var[B]) extends OneTail1[B]
      case class Zip[A1, A2](t1: OneTail1[A1], t2: OneTail1[A2], resultVar: Var[A1 |*| A2]) extends OneTail1[A1 |*| A2]
      case class Prj1[A, B](init: OneTail[A |*| B], resultVar: Var[A], residueVar: Var[B]) extends OneTail1[A]
      case class Prj2[A, B](init: OneTail[A |*| B], residueVar: Var[A], resultVar: Var[B]) extends OneTail1[B]

      def unzip[A, B](t: OneTail[A |*| B])(resultVar1: Var[A], resultVar2: Var[B]): (OneTail1[A], OneTail1[B]) =
        (Prj1(t, resultVar1, resultVar2), Prj2(t, resultVar1, resultVar2))
    }

    override def variable[A](v: Var[A]): Expr[A] =
      LambdasExpr(lambdas.Expr.variable(v))

    override def map[A, B](e: Expr[A], f: A -⚬ B, resultVar: Var[B]): Expr[B] =
      e match {
        case LambdasExpr(a) => LambdasExpr((a map f)(resultVar))
        case OneExpr(v, t)  => OneExpr(v, OneTail.Map(t, f, resultVar))
      }

    override def zip[A, B](a: Expr[A], b: Expr[B], resultVar: Var[A |*| B]): Expr[A |*| B] =
      (a, b) match {
        case (LambdasExpr(a), LambdasExpr(b)) =>
          LambdasExpr((a zip b)(resultVar))
        case (LambdasExpr(a), OneExpr(v, g)) =>
          val aOne: lambdas.Expr[A |*| One] =
            (a map smc.introSnd)(newSyntheticVar(a.terminalVars zip Vars.single(v)))
          val va = newSyntheticVar[A](hint = a.terminalVars)
          val (a1, o1) = lambdas.Expr.unzip(aOne)(va, v)
          LambdasExpr(lambdas.Expr.zip(a1, g(o1), resultVar))
        case (OneExpr(v, f), LambdasExpr(b)) =>
          val oneB: lambdas.Expr[One |*| B] =
            (b map smc.introFst)(newSyntheticVar(Vars.single(v) zip b.terminalVars))
          val vb = newSyntheticVar[B](hint = b.terminalVars)
          val (o1, b1) = lambdas.Expr.unzip(oneB)(v, vb)
          LambdasExpr(lambdas.Expr.zip(f(o1), b1, resultVar))
        case (a @ OneExpr(v, f), OneExpr(w, g)) =>
          val aOne: OneTail[A |*| One] =
            OneTail.Map(f, smc.introSnd, newSyntheticVar(a.terminalVars zip Vars.single(w)))
          val va = newSyntheticVar[A](hint = a.terminalVars)
          val (a1, o1) = OneTail.unzip(aOne)(va, w)
          OneExpr(v, OneTail.Zip(a1, g.apply1(o1), resultVar))
      }

    override def unzip[B1, B2](e: Expr[B1 |*| B2])(resultVar1: Var[B1], resultVar2: Var[B2]): (Expr[B1], Expr[B2]) =
      e match {
        case LambdasExpr(e) =>
          val (b1, b2) = lambdas.Expr.unzip(e)(resultVar1, resultVar2)
          (LambdasExpr(b1), LambdasExpr(b2))
        case OneExpr(v, f) =>
          val (f1, f2) = OneTail.unzip(f)(resultVar1, resultVar2)
          (OneExpr(v, f1), OneExpr(v, f2))
      }

    override def terminalVars[A](a: Expr[A]): Vars[A] =
      a match {
        case LambdasExpr(a) => a.terminalVars
        case OneExpr(v, f)  =>
          f.terminalVarsOpt match {
            case Right(va) => va
            case Left(ev)  => Vars.single(ev.substituteCo(v))
          }
      }

    def lift[A](expr: lambdas.Expr[A]): Expr[A] =
      LambdasExpr(expr)

    def one(v: Var[One]): Expr[One] =
      OneExpr(v, OneTail.Id)
  }

  override def abs[A, B](expr: Expr[B], boundVar: Var[A]): Abstracted[A, B] =
    expr match {
      case Expr.LambdasExpr(b) =>
        lambdas.abs(b, boundVar)
          .mapExpr[Expr]([X] => (x: lambdas.Expr[X]) => Expr.lift(x))
      case Expr.OneExpr(v, f) =>
        import Lambdas.Abstracted._
        val b = f(lambdas.Expr.variable(v))

        // boundVar will not be found,
        // because zipping with boundVar would have produced LambdasExpr
        // and other Expr constructors (Map, Prj1, Prj2)
        // don't bring a lambda-bound variable into the Expr
        lambdas.abs(b, boundVar) match {
          case NotFound(_) =>
            NotFound(expr)
          case Failure(e) =>
            Failure(e)
          case Exact(_, _) | Closure(_, _, _) =>
            throw new AssertionError(s"Did not expect to find variable $boundVar in $b, because $b is a constant expression")
        }
    }

  def compileConst[B](expr: Expr[B]): Either[Error, One -⚬ B] =
    expr match {
      case Expr.LambdasExpr(b) =>
        Left(Lambdas.Error.Undefined(b.initialVars))
      case Expr.OneExpr(v, f) =>
        import Lambdas.Abstracted.{Closure, Exact, Failure, NotFound}
        val b = f(lambdas.Expr.variable(v))
        lambdas.abs(b, v) match {
          case Exact(m, f) =>
            m match {
              case Multiplier.Id() => Right(f.fold)
              case _ => throw new AssertionError(s"Did not expect $v to be used multiple times in $b")
            }
          case Closure(captured, _, _) =>
            Left(Lambdas.Error.Undefined(
              captured.mapReduce0(
                [x] => (ex: lambdas.Expr[x]) => ex.initialVars,
                variables.union,
              )
            ))
          case Failure(e) =>
            Left(e)
          case NotFound(b) =>
            throw new AssertionError(s"Did not expect to not find variable $v in $b")
        }
    }
}

object LambdasOne {
  trait VarSynthesizer[Var[_], |*|[_, _]] {
    def newSyntheticVar[A](hint: Tupled[|*|, Var, ?]): Var[A]
  }
}
