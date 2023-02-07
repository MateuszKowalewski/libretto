package libretto

import libretto.util.SourcePos

trait InvertDSL extends ClosedDSL {
  /** `-[A]` is a demand for `A`.
    *
    * Inverts the flow of information: whatever travels through `A` in one direction,
    * travels through `-[A]` in the opposite direction.
    */
  type -[A]

  /**
    * ```
    *   ┏━━━━━━━━━━━┓
    *   ┞────┐      ┃
    *   ╎  A │┄┄┐   ┃
    *   ┟────┘  ┆   ┃
    *   ┃       ┆   ┃
    *   ┞────┐  ┆   ┃
    *   ╎-[A]│←┄┘   ┃
    *   ┟────┘      ┃
    *   ┗━━━━━━━━━━━┛
    * ```
    */
  def backvert[A]: (A |*| -[A]) -⚬ One

  /**
    * ```
    *   ┏━━━━━━┓
    *   ┃      ┞────┐
    *   ┃   ┌┄┄╎-[A]│
    *   ┃   ┆  ┟────┘
    *   ┃   ┆  ┃
    *   ┃   ┆  ┞────┐
    *   ┃   └┄→╎  A │
    *   ┃      ┟────┘
    *   ┗━━━━━━┛
    * ```
    */
  def forevert[A]: One -⚬ (-[A] |*| A)

  /**
    * ```
    *   ┏━━━━━━━━━━━┓
    *   ┃           ┞────┐
    *   ┞────┐      ╎-[A]│
    *   ╎ ⎡A⎤│      ┟────┘
    *   ╎-⎢⊗⎥│      ┃
    *   ╎ ⎣B⎦│      ┞────┐
    *   ┟────┘      ╎-[B]│
    *   ┃           ┟────┘
    *   ┗━━━━━━━━━━━┛
    * ```
    */
  def distributeInversion[A, B]: -[A |*| B] -⚬ (-[A] |*| -[B])

  /**
    * ```
    *   ┏━━━━━━━━━━━┓
    *   ┞────┐      ┃
    *   ╎-[A]│      ┞────┐
    *   ┟────┘      ╎ ⎡A⎤│
    *   ┃           ╎-⎢⊗⎥│
    *   ┞────┐      ╎ ⎣B⎦│
    *   ╎-[B]│      ┟────┘
    *   ┟────┘      ┃
    *   ┗━━━━━━━━━━━┛
    * ```
    */
  def factorOutInversion[A, B]: (-[A] |*| -[B]) -⚬ -[A |*| B]

  private val coreLib = CoreLib(this)
  import coreLib._

  override type =⚬[A, B] = -[A] |*| B

  override def eval[A, B]: ((A =⚬ B) |*| A) -⚬ B =
    swap > assocRL > elimFst(backvert)

  override def curry[A, B, C](f: (A |*| B) -⚬ C): A -⚬ (B =⚬ C) =
    introFst(forevert[B]) > assocLR > snd(swap > f)

  override def out[A, B, C](f: B -⚬ C): (A =⚬ B) -⚬ (A =⚬ C) =
    snd(f)

  /** Double-inversion elimination. */
  def die[A]: -[-[A]] -⚬ A =
    introSnd(forevert[A]) > assocRL > elimFst(swap > backvert[-[A]])

  /** Double-inversion introduction. */
  def dii[A]: A -⚬ -[-[A]] =
    introFst(forevert[-[A]]) > assocLR > elimSnd(swap > backvert[A])

  def contrapositive[A, B](f: A -⚬ B): -[B] -⚬ -[A] =
    introFst(forevert[A] > snd(f)) > assocLR > elimSnd(backvert[B])

  def unContrapositive[A, B](f: -[A] -⚬ -[B]): B -⚬ A =
    dii[B] > contrapositive(f) > die[A]

  def distributeInversionInto_|+|[A, B]: -[A |+| B] -⚬ (-[A] |&| -[B]) =
    choice(
      contrapositive(injectL[A, B]),
      contrapositive(injectR[A, B]),
    )

  def factorInversionOutOf_|+|[A, B]: (-[A] |+| -[B]) -⚬ -[A |&| B] =
    either(
      contrapositive(chooseL[A, B]),
      contrapositive(chooseR[A, B]),
    )

  def distributeInversionInto_|&|[A, B]: -[A |&| B] -⚬ (-[A] |+| -[B]) =
    unContrapositive(distributeInversionInto_|+| > |&|.bimap(die, die) > dii)

  def factorInversionOutOf_|&|[A, B]: (-[A] |&| -[B]) -⚬ -[A |+| B] =
    unContrapositive(die > |+|.bimap(dii, dii) > factorInversionOutOf_|+|)

  def invertClosure[A, B]: -[A =⚬ B] -⚬ (B =⚬ A) =
    distributeInversion > swap > snd(die)

  def unInvertClosure[A, B]: (A =⚬ B) -⚬ -[B =⚬ A] =
    snd(dii) > swap > factorOutInversion

  /** Uses the resource from the first in-port to satisfy the demand from the second in-port.
    * Alias for [[backvert]].
    */
  def supply[A]: (A |*| -[A]) -⚬ One =
    backvert[A]

  /** Creates a demand on the first out-port, channeling the provided resource to the second out-port.
    * Alias for [[forevert]].
    */
  def demand[A]: One -⚬ (-[A] |*| A) =
    forevert[A]

  /** Alias for [[die]]. */
  def doubleDemandElimination[A]: -[-[A]] -⚬ A =
    die[A]

  /** Alias for [[dii]]. */
  def doubleDemandIntroduction[A]: A -⚬ -[-[A]] =
    dii[A]

  /** Alias for [[distributeInversion]] */
  def demandSeparately[A, B]: -[A |*| B] -⚬ (-[A] |*| -[B]) =
    distributeInversion[A, B]

  /** Alias for [[factorOutInversion]]. */
  def demandTogether[A, B]: (-[A] |*| -[B]) -⚬ -[A |*| B] =
    factorOutInversion[A, B]

  /** Converts a demand for choice to a demand of the chosen side.
    * Alias for [[distributeInversionInto_|&|]].
    */
  def demandChosen[A, B]: -[A |&| B] -⚬ (-[A] |+| -[B]) =
    distributeInversionInto_|&|[A, B]

  /** Converts an obligation to handle either demand to an obligation to supply a choice.
    * Alias for [[factorInversionOutOf_|+|]].
    */
  def demandChoice[A, B]: (-[A] |+| -[B]) -⚬ -[A |&| B] =
    factorInversionOutOf_|+|[A, B]

  /** Converts demand for either to a choice of which side to supply.
    * Alias for [[distributeInversionInto_|+|]].
    */
  def toChoiceOfDemands[A, B]: -[A |+| B] -⚬ (-[A] |&| -[B]) =
    distributeInversionInto_|+|[A, B]

  /** Converts choice of demands to demand of either.
    * Alias for [[factorInversionOutOf_|&|]].
    */
  def demandEither[A, B]: (-[A] |&| -[B]) -⚬ -[A |+| B] =
    factorInversionOutOf_|&|[A, B]

  def invertedPingAsPong: -[Ping] -⚬ Pong =
    introFst(lInvertPongPing) > assocLR > elimSnd(supply[Ping])

  def pongAsInvertedPing: Pong -⚬ -[Ping] =
    introFst(demand[Ping]) > assocLR > elimSnd(rInvertPingPong)

  def invertedPongAsPing: -[Pong] -⚬ Ping =
    introSnd(lInvertPongPing) > assocRL > elimFst(swap > supply[Pong])

  def pingAsInvertedPong: Ping -⚬ -[Pong] =
    introSnd(demand[Pong] > swap) > assocRL > elimFst(rInvertPingPong)

  def invertedDoneAsNeed: -[Done] -⚬ Need =
    introFst(lInvertSignal) > assocLR > elimSnd(supply[Done])

  def needAsInvertedDone: Need -⚬ -[Done] =
    introFst(demand[Done]) > assocLR > elimSnd(rInvertSignal)

  def invertedNeedAsDone: -[Need] -⚬ Done =
    introSnd(lInvertSignal) > assocRL > elimFst(swap > supply[Need])

  def doneAsInvertedNeed: Done -⚬ -[Need] =
    introSnd(demand[Need] > swap) > assocRL > elimFst(rInvertSignal)

  def packDemand[F[_]]: -[F[Rec[F]]] -⚬ -[Rec[F]] =
    contrapositive(unpack[F])

  def unpackDemand[F[_]]: -[Rec[F]] -⚬ -[F[Rec[F]]] =
    contrapositive(pack[F])

  implicit class DemandExprOps[B](expr: $[-[B]]) {
    def contramap[A](f: A -⚬ B)(using
      pos: SourcePos,
      ctx: LambdaContext,
    ): $[-[A]] =
      $.map(expr)(contrapositive(f))(pos)
  }
}
