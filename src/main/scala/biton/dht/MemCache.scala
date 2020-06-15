/*
 * Copyright 2020 Kaspar Minosiants
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package biton.dht

import java.time.{ Clock, Instant }

import cats.effect.concurrent.Ref
import cats.effect.{ IO, Timer }
import cats.syntax.option._

import biton.dht.Conf.CacheExpiration

trait MemCache[A, B] {
  def get(key: A): IO[Option[B]]
  def put(key: A, value: B): IO[Unit]
  def purgeExpired: IO[Unit]
}
object MemCache {

  case class Timestamped[B](expires: Instant, value: B)

  object Timestamped {
    def apply[B](value: B, expires: CacheExpiration)(
        implicit clock: Clock
    ): Timestamped[B] =
      new Timestamped(
        Instant.now(clock).plusNanos(expires.value.toNanos),
        value
      )
  }

  def empty[A, B](
      expires: CacheExpiration
  )(implicit timer: Timer[IO], clock: Clock): IO[MemCache[A, B]] =
    Ref[IO].of(Map.empty[A, Timestamped[B]]).map { ref =>
      MemCacheMap(ref, expires)
    }

  final case class MemCacheMap[A, B](
      ref: Ref[IO, Map[A, Timestamped[B]]],
      expires: CacheExpiration
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

    override def purgeExpired: IO[Unit] = {
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
