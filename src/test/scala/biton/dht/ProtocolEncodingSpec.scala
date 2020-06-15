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
import org.scalacheck._

import cats.Eq
import cats.instances.list._
import cats.syntax.either._

import biton.dht.protocol.KMessage.{ NodesWithPeersResponse, _ }
import biton.dht.protocol._
import biton.dht.types.Node

import benc.{ BDecoder, BEncoder }

class ProtocolEncodingSpec extends KSuite {
  property("Ping")(prop[Ping])
  property("FindNode")(prop[FindNode])
  property("GetPeers")(prop[GetPeers])
  property("AnnouncePeer")(prop[AnnouncePeer])
  property("RpcErrorMessage")(prop[RpcErrorMessage])
  property("NodeIdResponse")(prop[NodeIdResponse])
  property("FindNodeResponse")(prop[FindNodeResponse])
  property("NodesWithPeersResponse")(prop[NodesWithPeersResponse])
  property("RpcError") {
    forAll(rpcErrorGen) { e =>
      val encoded = BEncoder[RpcError].encode(e)
      val decoded = encoded.flatMap(BDecoder[RpcError].decode)
      decoded === e.asRight
    }
  }

  property("list of nodes") {
    forAll(Gen.nonEmptyListOf(nodeGen())) { l =>
      val encoded = Node.bencoder.encode(l)
      val decoded = encoded.flatMap(Node.bdecoder.decode)
      decoded === l.asRight
    }
  }
  def prop[A <: KMessage: BEncoder: BDecoder: Gen: Eq]: Prop = {
    val gen = implicitly[Gen[A]]
    forAll(gen) { a =>
      val encoded = BEncoder[KMessage].encode(a)
      val decoded = encoded.flatMap(BDecoder[KMessage].decode)
      decoded === a.asRight
    }
  }

  val rpcErrorCodeGen: Gen[RpcErrorCode] =
    Gen.oneOf(201, 202, 203, 204).map(RpcErrorCode.find(_).get)

  def transactionGen: Gen[Transaction] = Gen.const(Random[Transaction].value)

  implicit val rpcErrorGen: Gen[RpcError] = for {
    code <- rpcErrorCodeGen
    msg  <- Gen.alphaStr
  } yield RpcError(code, msg)

  implicit val pingGen: Gen[Ping] = for {
    t      <- transactionGen
    nodeId <- nodeIdIntGen
  } yield Ping(t, nodeId)

  implicit val findNodeGen: Gen[FindNode] = for {
    t      <- transactionGen
    nodeId <- nodeIdIntGen
    target <- nodeIdIntGen
  } yield FindNode(t, nodeId, target)

  implicit val getPeersGen: Gen[GetPeers] = for {
    t        <- transactionGen
    nodeId   <- nodeIdIntGen
    infoHash <- infoHashGen
  } yield GetPeers(t, nodeId, infoHash)

  implicit val announcePeerGen: Gen[AnnouncePeer] = for {
    t        <- transactionGen
    ip       <- Gen.oneOf(true, false).map(ImpliedPort(_))
    id       <- nodeIdIntGen
    infoHash <- infoHashGen
    port     <- portGen
    token    <- tokenGen
  } yield AnnouncePeer(t, ip, id, infoHash, port, token)

  implicit val rpcErrorMessage: Gen[RpcErrorMessage] = for {
    t     <- transactionGen
    error <- rpcErrorGen
  } yield RpcErrorMessage(t, error)

  implicit val nodeIdResponseGen: Gen[NodeIdResponse] = for {
    t      <- transactionGen
    nodeId <- nodeIdIntGen
  } yield NodeIdResponse(t, nodeId)

  implicit val findNodeResponse: Gen[FindNodeResponse] = for {
    t      <- transactionGen
    nodeId <- nodeIdIntGen
    nodes  <- Gen.nonEmptyListOf(nodeGen())
  } yield FindNodeResponse(t, nodeId, nodes)

  implicit val nodesWithPeersResponseGen: Gen[NodesWithPeersResponse] = for {
    t      <- transactionGen
    nodeId <- nodeIdIntGen
    token  <- tokenGen
    nodes  <- Gen.option(Gen.nonEmptyListOf(nodeGen()))
    peers  <- Gen.option(Gen.nonEmptyListOf(peerGen))
  } yield NodesWithPeersResponse(t, nodeId, token, nodes, peers)

}
