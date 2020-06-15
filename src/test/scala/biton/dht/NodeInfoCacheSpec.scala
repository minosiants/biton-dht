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

import java.time.Clock

import scala.concurrent.duration._

import cats.instances.list._
import cats.instances.option._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.option._

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import biton.dht.Conf.CacheExpiration
import biton.dht.types.{ Distance, NodeInfo }

class NodeInfoCacheSpec extends KSuite {
  val _clock: Clock = Clock.systemDefaultZone()

  test("put/get") {
    forAll(infoHashGen, nodeInfoListGen) { (infohash, nodesInfo) =>
      val result = (for {
        cache <- NodeInfoCache.create(CacheExpiration(1.second))(
          ioTimer,
          _clock
        )
        _      <- cache.put(infohash, nodesInfo)
        result <- cache.get(infohash)
      } yield result).unsafeRunSync()

      result === nodesInfo.some
    }
  }
  test("expired") {

    forAll(infoHashGen, nodeInfoListGen) { (infohash, nodesInfo) =>
      val result = (for {
        cache <- NodeInfoCache.create(CacheExpiration(10.millis))(
          ioTimer,
          _clock
        )
        _      <- cache.put(infohash, nodesInfo)
        result <- ioTimer.sleep(11.millis) >> cache.get(infohash)
      } yield result).unsafeRunSync()

      result === none[List[NodeInfo]]
    }
  }

  val distanceGen: Gen[Distance] =
    Gen.negNum[Long].map(BigInt(_)).map(Distance(_))

}
