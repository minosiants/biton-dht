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
import scala.concurrent.duration._

import cats.syntax.eq._

import fs2.Stream

import biton.dht.Conf.SecretExpiration
class SecretsSpec extends KSuite {

  test("get the same secret") {

    val res = for {
      secrets <- Secrets.create(SecretExpiration(1.second))
      res <- Stream
        .emit(secrets)
        .concurrently(secrets.refresh())
        .evalMap { s =>
          for {
            s1 <- s.get
            s2 <- s.get
          } yield s1 === s2
        }
        .take(1)
        .compile
        .toList
    } yield res.head

    assert(res.unsafeRunSync())

  }
  test("get different secret") {
    val res = for {
      secrets <- Secrets.create(SecretExpiration(100.millis))
      res <- Stream
        .emit(secrets)
        .concurrently(secrets.refresh())
        .evalMap { s =>
          for {
            s1 <- s.get
            _  <- ioTimer.sleep(110.millis)
            s2 <- s.get
          } yield s1 =!= s2
        }
        .take(1)
        .compile
        .toList
    } yield res.head

    assert(res.unsafeRunSync())
  }

}
