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
