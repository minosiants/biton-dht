package kademlia

import java.time.{ Clock, Instant }
import java.util

import cats.effect.{ IO, Timer }
import cats.effect.concurrent.Ref
import cats.syntax.option._

import scala.concurrent.duration.FiniteDuration

trait MemCache[A, B] {
  def get(key: A): IO[Option[B]]
  def put(key: A, value: B): IO[Unit]
}
object MemCache {

  case class Timestamped[B](expires: Instant, value: B)

  object Timestamped {
    def apply[B](value: B, expires: FiniteDuration)(
        implicit clock: Clock
    ): Timestamped[B] =
      new Timestamped(Instant.now(clock).plusNanos(expires.toNanos), value)
  }

  def empty[A, B](
      expires: FiniteDuration
  )(implicit timer: Timer[IO], clock: Clock): IO[MemCache[A, B]] =
    Ref[IO].of(Map.empty[A, Timestamped[B]]).map { ref =>
      MemCacheMap(ref, expires)
    }

  final case class MemCacheMap[A, B](
      ref: Ref[IO, Map[A, Timestamped[B]]],
      expires: FiniteDuration
  )(implicit timer: Timer[IO], clock: Clock)
      extends MemCache[A, B] {

    def isExpired(expiration: Instant): Boolean =
      expiration.isBefore(Instant.now(clock))

    override def get(key: A): IO[Option[B]] =
      ref.modify { cache =>
        cache.get(key).fold(cache -> none[B]) {
          case Timestamped(expires, _) if isExpired(expires) =>
            cache.removed(key) -> none[B]
          case Timestamped(_, value) => cache -> value.some
        }
      }

    override def put(key: A, value: B): IO[Unit] =
      ref.update { cache =>
        cache ++ Map(key -> Timestamped(value, expires))
      }

    def purgeExpired: IO[Unit] = {
      ref.update { cache =>
        cache.keys.foldLeft(cache) { (c, key) =>
          c.get(key).fold(cache) {
            case Timestamped(expires, _) if isExpired(expires) =>
              cache.removed(key)
            case _ => cache
          }
        }
      }
    }
  }

}
