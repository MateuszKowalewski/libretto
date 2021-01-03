package libretto.impl

import java.util.concurrent.{ScheduledExecutorService, TimeUnit}
import libretto.ScalaRunner
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

/** Runner of [[FreeScalaDSL]] that returns a [[Future]].
  * 
  * It is known to be flawed by design in that it might create long (potentially infinite) chains of [[Promise]]s.
  * This could be solved with a custom implementation of promises/futures that support unregistration of listeners.
  * 
  * On top of that, expect bugs, since the implementation is full of unsafe type casts, because Scala's (including
  * Dotty's) type inference cannot cope with the kind of pattern matches found here.
  */
class FreeScalaFutureRunner(scheduler: ScheduledExecutorService) extends ScalaRunner[FreeScalaDSL.type, Future] {
  private implicit val ec: ExecutionContext =
    ExecutionContext.fromExecutor(scheduler)

  val dsl = FreeScalaDSL
  
  import dsl._
  
  private def bug[A](msg: String): A =
    throw new AssertionError(
      s"""$msg
         |This is a bug, please report at https://github.com/TomasMikula/libretto/issues/new?labels=bug"""
        .stripMargin
    )
    
  private def schedule[A](d: FiniteDuration, f: () => A): Future[A] = {
    val pa = Promise[A]()
    scheduler.schedule(() => pa.success(f()), d.length, d.unit)
    pa.future
  }

  override def runScala[A](prg: One -⚬ Val[A]): Future[A] =
    Frontier.One.extendBy(prg).getFuture
  
  private sealed trait Frontier[A] {
    import Frontier._
    
    def extendBy[B](f: A -⚬ B): Frontier[B] = {
      this match {
        case Deferred(fa) =>
          Deferred(fa.map(_.extendBy(f)))
        case _ =>
          f match {
            case -⚬.Id() =>
              this
            case -⚬.AndThen(f, g) =>
              extendBy(f).extendBy(g)
            case -⚬.Par(f, g) =>
              this match {
                case Pair(a1, a2) =>
                  type A1; type A2
                  Pair(
                    a1.asInstanceOf[Frontier[A1]].extendBy(f.asInstanceOf[A1 -⚬ Nothing]),
                    a2.asInstanceOf[Frontier[A2]].extendBy(g.asInstanceOf[A2 -⚬ Nothing]),
                  )                                                   .asInstanceOf[Frontier[B]]
                case other =>
                  bug(s"Did not expect $other to represent a pair (? |*| ?).")
              }
            case -⚬.IntroFst() =>
              Pair(One, this)
            case -⚬.IntroSnd() =>
              Pair(this, One)
            case -⚬.ElimFst() =>
              this match {
                case Pair(_, b) => b                                  .asInstanceOf[Frontier[B]]
              }
            case -⚬.ElimSnd() =>
              this match {
                case Pair(a, _) => a                                  .asInstanceOf[Frontier[B]]
              }
            case -⚬.TimesAssocLR() =>
              // ((X |*| Y) |*| Z) -⚬ (X |*| (Y |*| Z))
              type X; type Y
              this match {
                case Pair(Pair(x, y), z) =>
                  Pair(x, Pair(y, z))                                 .asInstanceOf[Frontier[B]]
                case Pair(Deferred(fxy), z) =>
                  Deferred(
                    fxy
                      .asInstanceOf[Future[Frontier[X |*| Y]]]
                      .map(xy => Pair(xy, z).extendBy(-⚬.TimesAssocLR()))
                  )                                                   .asInstanceOf[Frontier[B]]
                case other =>
                  bug(s"Did not expect $other to represent ((? |*| ?) |*| ?)")
              }
            case -⚬.TimesAssocRL() =>
              // (X |*| (Y |*| Z)) -⚬ ((X |*| Y) |*| Z)
              type Y; type Z
              this match {
                case Pair(x, Pair(y, z)) =>
                  Pair(Pair(x, y), z)                                 .asInstanceOf[Frontier[B]]
                case Pair(x, Deferred(fyz)) =>
                  Deferred(
                    fyz
                      .asInstanceOf[Future[Frontier[Y |*| Z]]]
                      .map(yz => Pair(x, yz).extendBy(-⚬.TimesAssocRL()))
                  )                                                   .asInstanceOf[Frontier[B]]
                case other =>
                  bug(s"Did not expect $other to represent (? |*| (? |*| ?))")
              }
            case -⚬.Swap() =>
              this match {
                case Pair(a, b) => Pair(b, a)                         .asInstanceOf[Frontier[B]]
              }
            case -⚬.InjectL() =>
              InjectL(this)                                           .asInstanceOf[Frontier[B]]
            case -⚬.InjectR() =>
              InjectR(this)                                           .asInstanceOf[Frontier[B]]
            case -⚬.EitherF(f, g) =>
              this match {
                case InjectL(e) =>
                  type A1
                  e.asInstanceOf[Frontier[A1]].extendBy(f.asInstanceOf[A1 -⚬ B])
                case InjectR(e) =>
                  type A2
                  e.asInstanceOf[Frontier[A2]].extendBy(g.asInstanceOf[A2 -⚬ B])
                case AsyncEither(fe) =>
                  type A1; type A2
                  Deferred(
                    fe.map {
                      case Left(a1)  => a1.asInstanceOf[Frontier[A1]].extendBy(f.asInstanceOf[A1 -⚬ B])
                      case Right(a2) => a2.asInstanceOf[Frontier[A2]].extendBy(g.asInstanceOf[A2 -⚬ B])
                    }
                  )
              }
            case -⚬.ChooseL() =>
              this match {
                case Choice(f, _, _) => f()                           .asInstanceOf[Frontier[B]]
              }
            case -⚬.ChooseR() =>
              this match {
                case Choice(_, g, _) => g()                           .asInstanceOf[Frontier[B]]
              }
            case -⚬.Choice(f, g) =>
              Choice(
                () => this.extendBy(f),
                () => this.extendBy(g),
                onError = this.crash(_),
              )
            case -⚬.DoneF() =>
              // Ignore `this`. It ends in `One`, so it does not need to be taken care of.
              DoneNow                                                 .asInstanceOf[Frontier[B]]
            case -⚬.NeedF() =>
              this match {
                case NeedAsync(f) => f.success(())
              }
              One                                                     .asInstanceOf[Frontier[B]]
            case -⚬.DelayIndefinitely() =>
              ???
            case -⚬.RegressInfinitely() =>
              ???
            case -⚬.Fork() =>
              Pair(this, this)                                        .asInstanceOf[Frontier[B]]
            case -⚬.Join() =>
              this match {
                case Pair(DoneNow, DoneNow) => DoneNow                .asInstanceOf[Frontier[B]]
                case Pair(DoneNow, d2: DoneAsync) => d2               .asInstanceOf[Frontier[B]]
                case Pair(d1: DoneAsync, DoneNow) => d1               .asInstanceOf[Frontier[B]]
                case Pair(DoneAsync(f1), DoneAsync(f2)) =>
                  DoneAsync((f1 zip f2).map(_ => ()))                 .asInstanceOf[Frontier[B]]
              }
            case -⚬.ForkNeed() =>
              this match {
                case NeedAsync(p) =>
                  val p1 = Promise[Any]().completeWith(p.future)
                  val p2 = Promise[Any]().completeWith(p.future)
                  Pair(NeedAsync(p1), NeedAsync(p2))                  .asInstanceOf[Frontier[B]]
              }
            case -⚬.JoinNeed() =>
              this match {
                case NeedAsync(p) =>
                  val p1 = Promise[Any]()
                  val p2 = Promise[Any]()
                  p.completeWith((p1.future zip p2.future).map(_ => ()))
                  Pair(NeedAsync(p1), NeedAsync(p2))                  .asInstanceOf[Frontier[B]]
              }
            case -⚬.SignalEither() =>
              ???
            case -⚬.SignalChoice() =>
              //        A             -⚬     B
              // (Need |*| (X |&| Y)) -⚬ (X |&| Y)
              type X; type Y
              this match {
                case Pair(n0, c0) =>
                  val n = n0.asInstanceOf[Frontier[Need]]
                  val c = c0.asInstanceOf[Frontier[X |&| Y]]
                  Choice(
                    () => {
                      Frontier.fulfillNeedWith(n, Future.successful(()))
                      Frontier.chooseL(c)
                    },
                    () => {
                      Frontier.fulfillNeedWith(n, Future.successful(()))
                      Frontier.chooseR(c)
                    },
                    onError = { e =>
                      Frontier.fulfillNeedWith(n, Future.failed(e))
                      Frontier.failChoice(c, e)
                    },
                  )                                                   .asInstanceOf[Frontier[B]]
              }
            case -⚬.InjectLWhenDone() =>
              // (Done |*| X) -⚬ ((Done |*| X) |+| Y)
              type X
              this match {
                case Pair(d, x) =>
                  d match {
                    case DoneNow =>
                      InjectL(Pair(DoneNow, x))                       .asInstanceOf[Frontier[B]]
                    case DoneAsync(fut) =>
                      AsyncEither(fut.map(_ => Left(Pair(DoneNow, x))))
                                                                      .asInstanceOf[Frontier[B]]
                    case Deferred(fut) =>
                      Deferred(
                        fut
                          .asInstanceOf[Future[Frontier[Done]]]
                          .map(d => Pair(d, x).extendBy(-⚬.InjectLWhenDone())))
                                                                      .asInstanceOf[Frontier[B]]
                  }
                case other =>
                  bug(s"Did not expect $other to represent (Done |*| ?)")
              }
            case -⚬.InjectRWhenDone() =>
              ???
            case -⚬.ChooseLWhenNeed() =>
              // ((Need |*| X) |&| Y) -⚬ (Need |*| X)
              type X
              this match {
                case Choice(nx, y, onError) =>
                  val pn = Promise[Any]()
                  val px = Promise[Frontier[X]]()
                  def go(nx: Frontier[Need |*| X]): Future[Frontier[X]] = {
                    nx match {
                      case Pair(n, x) =>
                        Future {
                          n match {
                            case NeedAsync(pn) => pn.success(()); x
                            case other => bug(s"Did not expect $other to represent Need")
                          }
                        }
                      case Deferred(fnx) =>
                        fnx.flatMap(go)
                      case other =>
                        Future { bug(s"Did not expect $other to represent (? |*| ?)") }
                    }
                  }
                  pn.future.onComplete {
                    case Failure(e) =>
                      onError(e)
                      px.failure(e)
                    case Success(_) =>
                      px.completeWith(go(nx().asInstanceOf[Frontier[Need |*| X]]))
                  }
                  Pair(NeedAsync(pn), Deferred(px.future))            .asInstanceOf[Frontier[B]]
                case other =>
                  bug(s"Did not expect $other to represent (? |&| ?)")
              }
            case -⚬.ChooseRWhenNeed() =>
              ???
            case -⚬.DistributeLR() =>
              // (X |*| (Y |+| Z)) -⚬ ((X |*| Y) |+| (X |*| Z))
              type X; type Y; type Z
              this match {
                case Pair(a, InjectL(f)) => InjectL(Pair(a, f))       .asInstanceOf[Frontier[B]]
                case Pair(a, InjectR(g)) => InjectR(Pair(a, g))       .asInstanceOf[Frontier[B]]
                case Pair(a, AsyncEither(f)) => AsyncEither(f.map {
                  case Left(b) => Left(Pair(a, b))
                  case Right(c) => Right(Pair(a, c))
                })                                                    .asInstanceOf[Frontier[B]]
                case Pair(x, Deferred(fyz)) =>
                  Deferred(fyz.map(yz =>
                    Pair(x, yz)                                       .asInstanceOf[Frontier[X |*| (Y |+| Z)]]
                      .extendBy(-⚬.DistributeLR[X, Y, Z]())           .asInstanceOf[Frontier[B]]
                  ))
              }
            case -⚬.CoDistributeL() =>
              // ((X |*| Y) |&| (X |*| Z)) -⚬ (X |*| (Y |&| Z))
              this match {
                case Choice(f, g, onError) =>
                  type X; type Y; type Z
                  val px = Promise[Frontier[X]]()
                  val chooseL: () => Frontier[Y] = { () =>
                    val (x, y) = Frontier.splitPair(f().asInstanceOf[Frontier[X |*| Y]])
                    px.success(x)
                    y
                  }
                  val chooseR: () => Frontier[Z] = { () =>
                    val (x, z) = Frontier.splitPair(g().asInstanceOf[Frontier[X |*| Z]])
                    px.success(x)
                    z
                  }
                  val onError1: Throwable => Unit = { e =>
                    onError(e)
                    px.failure(e)
                  }
                  Pair(Deferred(px.future), Choice(chooseL, chooseR, onError1)) .asInstanceOf[Frontier[B]]
              }
            case -⚬.RInvertSignal() =>
              this match {
                case Pair(done, NeedAsync(p)) =>
                  done match {
                    case DoneNow => p.success(())
                    case DoneAsync(f) => p.completeWith(f)
                  }
                  One                                                 .asInstanceOf[Frontier[B]]
              }
            case -⚬.LInvertSignal() =>
              this match {
                case One => 
                  val p = Promise[Any]()
                  Pair(NeedAsync(p), DoneAsync(p.future))             .asInstanceOf[Frontier[B]]
              }
            case r @ -⚬.RecF(_) =>
              this.extendBy(r.recursed)
            case -⚬.Pack() =>
              type F[_]
              Pack(this.asInstanceOf[Frontier[F[Rec[F]]]])            .asInstanceOf[Frontier[B]]
            case -⚬.Unpack() =>
              this match {
                case Pack(f) => f                                     .asInstanceOf[Frontier[B]]
              }
            case -⚬.RaceCompletion() =>
              this match {
                case Pair(DoneNow, y) => InjectL(y)                   .asInstanceOf[Frontier[B]]
                case Pair(x, DoneNow) => InjectR(x)                   .asInstanceOf[Frontier[B]]
                case Pair(DoneAsync(fx), DoneAsync(fy)) =>
                  val p = Promise[Either[Frontier[Done], Frontier[Done]]]
                  fx.onComplete(r => p.tryComplete(r.map(_ => Left(DoneAsync(fy)))))
                  fy.onComplete(r => p.tryComplete(r.map(_ => Right(DoneAsync(fx)))))
                  AsyncEither(p.future)                               .asInstanceOf[Frontier[B]]
              }
            case -⚬.SelectRequest() =>
              this match {
                case Choice(f, g, onError) =>
                  val p1 = Promise[Any]()
                  val p2 = Promise[Any]()
                  val p = Promise[(() => Frontier[Need], Future[Any])]
                  p1.future.onComplete(r => p.tryComplete(r.map(_ => (f.asInstanceOf[() => Frontier[Need]], p2.future))))
                  p2.future.onComplete(r => p.tryComplete(r.map(_ => (g.asInstanceOf[() => Frontier[Need]], p1.future))))
                  p.future.onComplete {
                    case Success((n, fut)) => Frontier.fulfillNeedWith(n(), fut)
                    case Failure(e) => onError(e)
                  }
                  Pair(NeedAsync(p1), NeedAsync(p2))                  .asInstanceOf[Frontier[B]]
              }
            case -⚬.Crash(msg) =>
              val e = Crash(msg)
              this.crash(e)
              Deferred(Future.failed(e))
            case -⚬.Delay(d) =>
              this match {
                case DoneNow =>
                  DoneAsync(schedule(d, () => ()))                    .asInstanceOf[Frontier[B]]
                case DoneAsync(fut) =>
                  DoneAsync(fut.flatMap(_ => schedule(d, () => ())))  .asInstanceOf[Frontier[B]]
                case other =>
                  bug(s"Did not expect $other to represent Done")
              }
            case -⚬.Promise() =>
              this match {
                case One =>
                  type X
                  val px = Promise[X]()
                  Pair(Prom(px), Fut(px.future))                      .asInstanceOf[Frontier[B]]
              }
            case -⚬.Fulfill() =>
              this match {
                case Pair(Fut(fa), Prom(pa)) =>
                  type X
                  val px = pa.asInstanceOf[Promise[X]]
                  val fx = fa.asInstanceOf[Future[X]]
                  px.completeWith(fx)
                  One                                                 .asInstanceOf[Frontier[B]]
              }
            case -⚬.LiftEither() =>
              this match {
                case Fut(fe) =>
                  type A1; type A2
                  AsyncEither(
                    fe
                      .asInstanceOf[Future[Either[A1, A2]]]
                      .map {
                        case Left(a1)  => Left (Fut(Future.successful(a1)))
                        case Right(a2) => Right(Fut(Future.successful(a2)))
                      }
                  )                                                   .asInstanceOf[Frontier[B]]
                case other =>
                  bug(s"Did not expect $other to represent a Val")
              }
            case -⚬.UnliftEither() =>
              ???
            case -⚬.LiftPair() =>
              this match {
                case Fut(fa) =>
                  type A1; type A2 // such that A = Val[(A1, A2)]
                  val g = fa.asInstanceOf[Future[(A1, A2)]]
                  Pair(Fut(g.map(_._1)), Fut(g.map(_._2)))            .asInstanceOf[Frontier[B]]
              }
            case -⚬.UnliftPair() =>
              //          A          -⚬    B
              // (Val[X] |*| Val[Y]) -⚬ Val[(X, Y)]
              def go[X, Y](f: Frontier[Val[X] |*| Val[Y]]): Frontier[Val[(X, Y)]] =
                f match {
                  case Pair(Deferred(fa1), a2) =>
                    Deferred(fa1.map(a1 => go(Pair(a1, a2))))
                  case Pair(a1, Deferred(fa2)) =>
                    Deferred(fa2.map(a2 => go(Pair(a1, a2))))
                  case Pair(Fut(fa1), Fut(fa2)) =>
                    Fut(fa1 zip fa2)
                }
              type X; type Y
              go(this.asInstanceOf[Frontier[Val[X] |*| Val[Y]]])      .asInstanceOf[Frontier[B]]
            case -⚬.LiftNegPair() =>
              ???
            case -⚬.UnliftNegPair() =>
              ???
            case -⚬.LiftV(f) =>
              this match {
                case Fut(fa) =>
                  Fut(fa.map(f.asInstanceOf[Any => Nothing]))         .asInstanceOf[Frontier[B]]
                case other =>
                  bug(s"Did not expect $other to represent a Val")
              }
            case -⚬.LiftN(f) =>
              this match {
                case Prom(pa) =>
                  type B0
                  val pb = Promise[B0]()
                  pa.completeWith(pb.future.map(f.asInstanceOf[B0 => Nothing]))
                  Prom(pb)                                            .asInstanceOf[Frontier[B]]
                case other =>
                  bug(s"Did not expect $other to represent a Neg")
              }
            case -⚬.ConstVal(a) =>
              this match {
                case DoneNow => Fut(Future.successful(a))             .asInstanceOf[Frontier[B]]
                case DoneAsync(f) => Fut(f.map(_ => a))               .asInstanceOf[Frontier[B]]
              }
            case -⚬.ConstNeg(a) =>
              this match {
                case Prom(pa) =>
                  val pu = Promise[Any]()
                  pa.completeWith(pu.future.map(_ => a).asInstanceOf[Future[Nothing]])
                  NeedAsync(pu)                                       .asInstanceOf[Frontier[B]]
              }
            case -⚬.Neglect() =>
              this match {
                case Fut(fa) => DoneAsync(fa)                         .asInstanceOf[Frontier[B]]
              }
            case -⚬.Inflate() =>
              ???
          }
      }
    }

    def getFuture[R](implicit ev: A =:= Val[R]): Future[R] = {
      val self: Frontier[Val[R]] = ev.substituteCo(this)
      self match {
        case Fut(f) => f
        case Deferred(f) => f.flatMap(_.getFuture)
        case other => bug(s"Did not expect $other to represent Val[?]")
      }
    }
    
    private def crash(e: Throwable): Unit = {
      this match {
        case One | DoneNow | DoneAsync(_) | Fut(_) =>
          // do nothing
        case NeedAsync(promise) =>
          promise.failure(e)
        case Pair(a, b) =>
          a.crash(e)
          b.crash(e)
        case InjectL(a) =>
          a.crash(e)
        case InjectR(b) =>
          b.crash(e)
        case AsyncEither(fab) =>
          fab.map(_.fold(_.crash(e), _.crash(e)))
        case Choice(_, _, onError) =>
          onError(e)
        case Deferred(fa) =>
          fa.map(_.crash(e))
        case Pack(f) =>
          f.crash(e)
        case Prom(promise) =>
          promise.failure(e)
      }
    }
  }
  
  private object Frontier {
    case object One extends Frontier[dsl.One]
    case object DoneNow extends Frontier[Done]
    case class DoneAsync(future: Future[_]) extends Frontier[Done]
    case class NeedAsync(promise: Promise[Any]) extends Frontier[Need]
    case class Pair[A, B](a: Frontier[A], b: Frontier[B]) extends Frontier[A |*| B]
    case class InjectL[A, B](a: Frontier[A]) extends Frontier[A |+| B]
    case class InjectR[A, B](b: Frontier[B]) extends Frontier[A |+| B]
    // TODO: Deferred[A |+| B] might be a sufficient replacement for AsyncEither[A, B]
    case class AsyncEither[A, B](f: Future[Either[Frontier[A], Frontier[B]]]) extends Frontier[A |+| B]
    case class Choice[A, B](a: () => Frontier[A], b: () => Frontier[B], onError: Throwable => Unit) extends Frontier[A |&| B]
    case class Deferred[A](f: Future[Frontier[A]]) extends Frontier[A]
    case class Pack[F[_]](f: Frontier[F[Rec[F]]]) extends Frontier[Rec[F]]
    
    case class Fut[A](f: Future[A]) extends Frontier[Val[A]]
    case class Prom[A](p: Promise[A]) extends Frontier[Neg[A]]
    
    def fulfillNeedWith(n: Frontier[Need], f: Future[Any]): Unit =
      n match {
        case NeedAsync(p) =>
          p.completeWith(f)
        case Deferred(fn) =>
          fn.onComplete {
            case Success(n) => fulfillNeedWith(n, f)
            case Failure(e) => // do nothing, an error is already propagating
          }
      }
      
    def chooseL[A, B](f: Frontier[A |&| B]): Frontier[A] =
      f match {
        case Choice(a, b, onError) => a()
        case Deferred(f) => Deferred(f.map(chooseL))
      }
      
    def chooseR[A, B](f: Frontier[A |&| B]): Frontier[B] =
      f match {
        case Choice(a, b, onError) => b()
        case Deferred(f) => Deferred(f.map(chooseR))
      }

    def failChoice[A, B](f: Frontier[A |&| B], e: Throwable): Unit =
      f match {
        case Choice(a, b, onError) => onError(e)
        case Deferred(f) => f.onComplete {
          case Success(f) => failChoice(f, e)
          case Failure(ex) => // do nothing, the error is already propagating
        }
      }
      
    def splitPair[A, B](f: Frontier[A |*| B]): (Frontier[A], Frontier[B]) =
      f match {
        case Pair(a, b) => (a, b)
        case Deferred(f) =>
          val fab = f.map(splitPair)
          (Deferred(fab.map(_._1)), Deferred(fab.map(_._2)))
      }
  }
  
  private case class Crash(msg: String) extends Exception(msg)
}
