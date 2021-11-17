package libretto.impl

import libretto.scalasource.Position

sealed trait VarOrigin {
  import VarOrigin._

  def print: String =
    this match {
      case FunApp(Position(f, l))     => s"Result of function application at $f:$l"
      case Pairing(Position(f, l))    => s"Pair created at $f:$l"
      case Prj1(Position(f, l))       => s"First half of untupling at $f:$l"
      case Prj2(Position(f, l))       => s"Second half of untupling at $f:$l"
      case Lambda(Position(f, l))     => s"Introduced by lambda expression ending at $f:$l"
      case ClosureVal(Position(f, l)) => s"Value of closure expression at $f:$l"
    }
}

object VarOrigin {
  case class FunApp(pos: Position) extends VarOrigin
  case class Pairing(pos: Position) extends VarOrigin
  case class Prj1(pos: Position) extends VarOrigin
  case class Prj2(pos: Position) extends VarOrigin
  case class Lambda(pos: Position) extends VarOrigin
  case class ClosureVal(pos: Position) extends VarOrigin
}
