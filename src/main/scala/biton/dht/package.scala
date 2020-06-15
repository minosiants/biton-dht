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

package biton

import scala.Function.const

import com.comcast.ip4s.{ IpAddress, Port }

import cats.instances.either._
import cats.instances.list._
import cats.kernel.Eq
import cats.syntax.either._
import cats.syntax.foldable._
import cats.{ Monoid, Order }

import scodec.bits.{ BitVector, ByteVector }

import biton.dht.syntax.Syntax

package object dht extends Codecs with Syntax {

  type Result[A] = Either[Error, A]

  val idLength                 = 20 * 8
  val highestNodeId: BitVector = BitVector.high(idLength)
  val lowestNodeId: BitVector  = BitVector.low(idLength)

  implicit val orderByteVector: Order[ByteVector] = Order.from[ByteVector] {
    (a, b) =>
      val result = a.toSeq.toList
        .zip(b.toSeq)
        .foldM[Either[Int, *], List[Byte]](List.empty) {
          case (b, (aa, bb)) if aa.ubyte == bb.ubyte => (aa :: b).asRight
          case (_, (aa, bb)) if aa.ubyte > bb.ubyte  => 1.asLeft
          case (_, (aa, bb)) if aa.ubyte < bb.ubyte  => (-1).asLeft
        }
      result.fold(identity, const(0))
  }

  implicit val eqBitVector: Eq[BitVector] = Eq.instance { (a, b) =>
    a === b
  }
  implicit val eqPort: Eq[Port]           = Eq.instance(_.equals(_))
  implicit val eqIpAddress: Eq[IpAddress] = Eq.instance(_.equals(_))

  implicit def tupleMonoid[A, B]: Monoid[(List[A], List[B])] = Monoid.instance(
    (List.empty[A], List.empty[B]),
    (a, b) => {
      val (a1, b1) = a
      val (a2, b2) = b
      (a1 ++ a2) -> (b1 ++ b2)
    }
  )

}
