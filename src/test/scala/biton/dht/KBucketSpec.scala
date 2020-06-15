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

import java.time.LocalDateTime

import biton.dht.types.{ KSize, NodeActivity, NodeId, Prefix }
import cats.instances.vector._
import cats.syntax.either._
import org.scalacheck.Prop.forAll
import scala.concurrent.duration._

class KBucketSpec extends KSuite {

  val ksize = KSize(3)
  val from  = Prefix(0)
  val to    = Prefix(10)

  def kbGen(ksize: KSize = ksize, nsize: Int = ksize.value) =
    kbucketGen(from, to, ksize, nsize)

  test("add to full kbucket") {
    forAll(kbGen()) { kbucket =>
      val id     = availableIds(kbucket).head
      val node   = kbucket.nodes.value.head.node.copy(nodeId = NodeId.fromInt(id))
      val result = kbucket.add(node)
      result === Error.KBucketError(s"$kbucket is full").asLeft
    }
  }

  test("add to empty kbucket") {
    forAll(kbGen(ksize, 0), nodeGen(nodeIdChooseGen(0, 9))) { (kbucket, node) =>
      val result = kbucket.add(node)
      result === KBucket.create(
        kbucket.from,
        kbucket.to,
        Nodes(Vector(NodeActivity(node)), ksize)
      )
    }
  }

  test("add to kbucket") {
    forAll(kbGen(ksize, 2)) { kbucket =>
      val node = kbucket.nodes.value.head.node
      val result = for {
        kb  <- kbucket.remove(node)
        res <- kb.add(node)
      } yield res
      result.map(_.nodes.value) === kbucket.nodes.value.reverse.asRight
    }
  }

  test("split kbucket") {
    forAll(kbGen(ksize)) { kbucket =>
      val result = for {
        (first, second) <- kbucket.split()
      } yield checkBuckets(first, second)

      result == true.asRight
    }
  }
  test("inRange") {
    forAll(kbGen(ksize), nodeGen(nodeIdChooseGen(5, 9))) { (kbucket, node) =>
      val result = for {
        (_, second) <- kbucket.split()
      } yield second.inRange(node.nodeId)

      result == true.asRight
    }
  }

  test("outdatedNodes") {
    forAll(kbGen()) { kbucket =>
      kbucket
        .outdatedNodes(LocalDateTime.now(clock).plusNanos(1.minute.toNanos))
        .size == ksize.value
    }
  }
  test("findBad") {
    forAll(kbGen()) { kbucket =>
      val n      = kbucket.random
      val result = kbucket.fail(n).map(_.findBad)
      result.map(_.isDefined) == true.asRight
    }
  }

  test("swap") {
    forAll(kbGen(), nodeGen()) { (kbucket, replacement) =>
      val n      = kbucket.random
      val result = kbucket.swap(n, replacement)
      result.map(_.find(n.nodeId).isEmpty) == true.asRight &&
      result.map(_.find(replacement.nodeId).isDefined) == true.asRight
    }
  }

  def checkBuckets(first: KBucket, second: KBucket): Boolean = {
    val firstResult = first.nodes.value.foldLeft(true) { (v, n) =>
      first.inRange(n.node.nodeId) && v
    }
    val secondResult = second.nodes.value.foldLeft(true) { (v, n) =>
      second.inRange(n.node.nodeId) && v
    }
    firstResult && secondResult
  }
}
