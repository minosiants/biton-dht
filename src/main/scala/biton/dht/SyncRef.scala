package biton.dht

import cats.implicits._
import cats.effect.{ Concurrent, IO }
import cats.effect.concurrent.{ Deferred, Ref }

trait SyncRef[A] {
  def update(f: A => IO[A]): IO[A]
  def get: IO[A]
}

object SyncRef {

  def create[A](v: => A)(implicit c: Concurrent[IO]): IO[SyncRef[A]] = {
    type Res = Either[Throwable, A]
    type Def = Deferred[IO, Res]
    def deferred = Deferred[IO, Res]

    sealed trait State
    case class Value(v: A)      extends State
    case class Updating(d: Def) extends State

    Ref.of[IO, State](Value(v)).map { state =>
      new SyncRef[A] {
        override def update(f: A => IO[A]): IO[A] = {
          deferred.flatMap { newValue =>
            state.modify {
              case Value(v) =>
                Updating(newValue) -> _update(f, v, newValue).rethrow
              case Updating(d) =>
                Updating(newValue) -> d.get.rethrow.flatMap { t =>
                  _update(f, t, newValue).rethrow
                }
            }.flatten
          }
        }
        def _update(f: A => IO[A], v: A, d: Def): IO[Res] = {
          for {
            res <- f(v).attempt
            _ <- state.set {
              res match {
                case Left(_) =>
                  Value(v)
                case Right(value) =>
                  Value(value)
              }
            }
            _ <- d.complete(res)
          } yield res
        }

        override def get: IO[A] = {
          state.get.flatMap {
            case Value(v)    => IO(v)
            case Updating(d) => d.get.rethrow
          }
        }
      }
    }
  }
}
