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

import org.scalacheck.Prop._

import cats.implicits._

import types.KSize

class NodesSpec extends KSuite {

  test("filterNot") {
    forAll(vectorOfNodesAcitvity(5), nodeGen()) { (vec, node) =>
      val nodes = Nodes(vec.filterNot(_.node.nodeId === node.nodeId), KSize(6))
      val result = for {
        r <- nodes.append(node)
      } yield r.filterNot(node)
      result === nodes.asRight
    }
  }

  test("append") {
    forAll(vectorOfNodesAcitvity(5), nodeGen()) { (vec, node) =>
      val nodes  = Nodes(vec.filterNot(_.node.nodeId === node.nodeId), KSize(6))
      val result = nodes.append(node)
      result.map(_.value.last.node) === node.asRight
    }
  }

  test("append when full") {
    forAll(vectorOfNodesAcitvity(5), nodeGen()) { (vec, node) =>
      val nodes  = Nodes(vec, KSize(5))
      val result = nodes.append(node)
      result === Error.KBucketError(s"Bucket is full for Node $node").asLeft
    }
  }

}
