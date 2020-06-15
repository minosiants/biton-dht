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

import cats.syntax.partialOrder._

import org.scalacheck.Prop._
import org.scalacheck._

import biton.dht.types.NodeId

class NodeIdSute extends KSuite {

  property("id is comparable") {
    forAll(intPairGen) {
      case (a, b) =>
        val aId = NodeId.fromInt(a)
        val bId = NodeId.fromInt(b)
        (aId > bId) == (a > b)
    }

  }

  val intPairGen: Gen[(Int, Int)] = for {
    a <- Gen.posNum[Int]
    b <- Gen.posNum[Int]
  } yield (a, b)

  val intTripleGen: Gen[(Int, Int, Int)] = for {
    a <- Gen.posNum[Int]
    b <- Gen.posNum[Int]
    c <- Gen.posNum[Int]
  } yield (a, b, c)
}
