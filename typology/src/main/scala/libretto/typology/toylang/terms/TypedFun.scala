package libretto.typology.toylang.terms

import libretto.typology.toylang.types.{Fix, RecCall, Type, TypeTag}

sealed trait TypedFun[A, B]

object TypedFun {
  case class Id[A](typ: Type) extends TypedFun[A, A]
  case class AndThen[A, X, B](f: TypedFun[A, X], tx: Type, g: TypedFun[X, B]) extends TypedFun[A, B]
  case class Par[A1, A2, B1, B2](f1: TypedFun[A1, B1], f2: TypedFun[A2, B2]) extends TypedFun[(A1, A2), (B1, B2)]
  case class EitherF[A1, A2, B](f1: TypedFun[A1, B], f2: TypedFun[A2, B]) extends TypedFun[Either[A1, A2], B]
  case class InjectL[A, B](ta: Type, tb: Type) extends TypedFun[A, Either[A, B]]
  case class InjectR[A, B](ta: Type, tb: Type) extends TypedFun[B, Either[A, B]]
  case class Rec[A, B](f: TypedFun[(RecCall[A, B], A), B]) extends TypedFun[A, B]
  case class Recur[A, B](ta: Type, tb: Type) extends TypedFun[(RecCall[A, B], A), B]
  case class FixF[F[_]](f: TypeTag[F]) extends TypedFun[F[Fix[F]], Fix[F]]
  case class UnfixF[F[_]](f: TypeTag[F]) extends TypedFun[Fix[F], F[Fix[F]]]

  def id[A](typ: Type): TypedFun[A, A] = Id(typ)
  def andThen[A, X, B](f: TypedFun[A, X], tx: Type, g: TypedFun[X, B]): TypedFun[A, B] = AndThen(f, tx, g)
  def par[A1, A2, B1, B2](f1: TypedFun[A1, B1], f2: TypedFun[A2, B2]): TypedFun[(A1, A2), (B1, B2)] = Par(f1, f2)
  def either[A1, A2, B](f1: TypedFun[A1, B], f2: TypedFun[A2, B]): TypedFun[Either[A1, A2], B] = EitherF(f1, f2)
  def injectL[A, B](ta: Type, tb: Type): TypedFun[A, Either[A, B]] = InjectL(ta, tb)
  def injectR[A, B](ta: Type, tb: Type): TypedFun[B, Either[A, B]] = InjectR(ta, tb)
  def rec[A, B](f: TypedFun[(RecCall[A, B], A), B]): TypedFun[A, B] = Rec(f)
  def recur[A, B](ta: Type, tb: Type): TypedFun[(RecCall[A, B], A), B] = Recur(ta, tb)
  def fix[F[_]](f: TypeTag[F]): TypedFun[F[Fix[F]], Fix[F]] = FixF(f)
  def unfix[F[_]](f: TypeTag[F]): TypedFun[Fix[F], F[Fix[F]]] = UnfixF(f)
}
