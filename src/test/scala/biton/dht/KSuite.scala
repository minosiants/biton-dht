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

import java.time.{ Clock, Instant, ZoneOffset }

import scala.concurrent.ExecutionContext

import com.comcast.ip4s.{ IpAddress, Port }

import cats.effect.{ ContextShift, IO, Timer }
import cats.instances.list._
import cats.syntax.traverse._

import scodec.bits.BitVector

import org.scalacheck.Gen
import org.scalacheck.cats.implicits._

import biton.dht.protocol.{ InfoHash, Peer, Token }
import biton.dht.types._

import munit.ScalaCheckSuite

class KSuite extends ScalaCheckSuite with KGens

trait KImplicits {
  implicit val clock: Clock                      = Clock.fixed(Instant.now(), ZoneOffset.UTC)
  private val executionContext: ExecutionContext = ExecutionContext.global

  implicit val ioContextShift: ContextShift[IO] =
    IO.contextShift(executionContext)

  implicit val ioTimer: Timer[IO] = IO.timer(executionContext)

  def ordered(nodes: List[Node], target: NodeId) = {
    nodes.sliding(2).forall {
      case x :: y :: Nil =>
        x.nodeId.distance(target) <= y.nodeId.distance(target)
      case _ => true
    }
  }
}

trait KGens extends KImplicits {
  val nodeIdIntGen: Gen[NodeId] =
    Gen.chooseNum(0, Integer.MAX_VALUE).map(NodeId.fromInt)

  def bitVectorGen(size: Int = idLength): Gen[BitVector] =
    Gen
      .infiniteStream(Gen.chooseNum(0, 255))
      .map(_.take(size).toList.map(_.toByte))
      .map(BitVector(_))

  val nodeIdCharGen: Gen[NodeId] = bitVectorGen(20).map(NodeId((_)))

  val byteGen: Gen[Byte] = Gen.chooseNum(0, 255).map(_.byteValue())

  val ipV4Gen: Gen[IpAddress] =
    Gen
      .infiniteStream(byteGen)
      .map(_.take(4).toArray)
      .map(IpAddress.fromBytes(_).get)

  val portGen: Gen[Port] = Gen.chooseNum(0, 65535).map(Port(_).get)

  val peerGen: Gen[Peer] = for {
    ip   <- ipV4Gen
    port <- portGen
  } yield Peer(ip, port)

  val contactGen: Gen[Contact] = for {
    ip   <- ipV4Gen
    port <- portGen
  } yield Contact(ip, port)

  def nodeGen(nodeIdGen: Gen[NodeId] = nodeIdCharGen): Gen[Node] =
    for {
      id      <- nodeIdGen
      contact <- contactGen
    } yield Node(id, contact)

  def listOfNodesGen(
      size: Int,
      nodeIdGen: Gen[NodeId] = nodeIdCharGen
  ): Gen[List[Node]] =
    for {
      list <- Gen
        .infiniteStream(nodeIdGen)
        .map(_.take(size * 30).distinct.take(size).toList)
        .retryUntil(_.size == size)
      res <- list.traverse(v => contactGen.map(Node(v, _)))
    } yield res

  def vectorOfNodesAcitvity(size: Int, nodeIdGen: Gen[NodeId] = nodeIdCharGen) =
    listOfNodesGen(size, nodeIdGen).map(_.toVector.map(NodeActivity(_)))
  def nodeIdChooseGen(from: Int, to: Int): Gen[NodeId] =
    Gen.choose(from, to).map(NodeId.fromInt)

  def kbucketGen(
      from: Prefix,
      to: Prefix,
      ksize: KSize,
      nsize: Int
  ): Gen[KBucket] =
    for {
      nodes <- listOfNodesGen(
        nsize,
        nodeIdChooseGen(from.value.toInt, to.value.toInt - 1)
      )
    } yield KBucket
      .create(from, to, Nodes(nodes.map(NodeActivity(_)).toVector, ksize))
      .toOption
      .get

  def availableIds(kb: KBucket): Set[Int] = {
    val ids =
      kb.nodes.value.map(v => BigInt(v.node.nodeId.value.toByteArray).toInt)
    Set.range(kb.from.value.toInt, kb.to.value.toInt) &~ ids.toSet
  }

  val tokenGen: Gen[Token] =
    bitVectorGen(2 * 8).map(Token(_))

  val infoHashGen: Gen[InfoHash] =
    Gen.negNum[Long].map(BitVector.fromLong(_)).map(InfoHash(_))

  val nodeInfoGen: Gen[NodeInfo] = for {
    token <- tokenGen
    node  <- nodeGen()
  } yield NodeInfo(token, node)

  val nodeInfoListGen: Gen[List[NodeInfo]] = Gen.listOf(nodeInfoGen)

}
