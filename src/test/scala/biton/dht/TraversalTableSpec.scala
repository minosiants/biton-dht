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

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import cats.implicits._

import biton.dht.TraversalNode.{ Responded, Stale }
import biton.dht.types.NodeInfo

class TraversalTableSpec extends KSuite {

  def isSorted[A](l: List[TraversalNode[A]]) = l match {
    case Nil => true
    case xs =>
      xs.sliding(2).forall {
        case x :: y :: Nil => x.distance <= y.distance
        case _             => true
      }
  }

  test("create") {
    forAll(infoHashGen, listOfNodesGen(5)) { (infohash, nodes) =>
      TraversalTable.create[NodeInfo](infohash.toNodeId, nodes, 8) match {
        case TraversalTable.Completed(_, nodes, _)  => isSorted(nodes)
        case TraversalTable.InProgress(_, nodes, _) => isSorted(nodes)
      }
    }
  }

  test("markNodesAsStale") {
    forAll(infoHashGen, Gen.nonEmptyListOf(nodeGen())) { (infohash, nodes) =>
      val table         = TraversalTable.create(infohash.toNodeId, nodes, 8)
      val expected      = table.nodes.takeRight(1)
      val nodesToUpdate = expected.map(_.node)
      val t             = table.markNodesAsStale(nodesToUpdate)
      val result        = t.nodes.takeRight(1).collect { case Stale(n, _) => n }
      expected.map(_.node) === result
    }
  }
  test("markNodesAsResponded") {
    forAll(infoHashGen, Gen.nonEmptyListOf(nodeGen()), tokenGen) {
      (infohash, nodes, token) =>
        val table         = TraversalTable.create[NodeInfo](infohash.toNodeId, nodes, 8)
        val sizeToTake    = table.nodes.size / 2
        val expected      = table.nodes.takeRight(sizeToTake)
        val nodesToUpdate = expected.map(v => NodeInfo(token, v.node))
        val t             = table.markNodesAsResponded(nodesToUpdate)(_.node)
        val result =
          t.nodes.takeRight(sizeToTake).collect { case Responded(n, _, _) => n }
        expected.map(_.node) === result
    }
  }
  test("completed") {
    forAll(infoHashGen, listOfNodesGen(5), tokenGen) {
      (infohash, nodes, token) =>
        val table                   = TraversalTable.create[NodeInfo](infohash.toNodeId, nodes, 3)
        val a :: b :: c :: d :: Nil = table.topFresh(4)
        val result = table
          .markNodesAsResponded(
            List(NodeInfo(token, a), NodeInfo(token, c), NodeInfo(token, d))
          )(_.node)
          .markNodeAsStale(b)
        result match {
          case TraversalTable.Completed(_, _, _)  => true
          case TraversalTable.InProgress(_, _, _) => false
        }

    }

  }

  test("order by distance") {
    forAll(infoHashGen, Gen.nonEmptyListOf(nodeGen())) { (infohash, nodes) =>
      val table = TraversalTable.create[NodeInfo](infohash.toNodeId, nodes, 8)
      val order = table.nodes.sliding(2).forall {
        case x :: y :: Nil => x.distance < y.distance
        case _ :: Nil      => true
        case _             => false
      }
      order
    }
  }
}
