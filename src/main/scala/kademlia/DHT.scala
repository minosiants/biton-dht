package kademlia

import java.net.InetSocketAddress
import java.time.Clock

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, ContextShift, IO}
import kademlia.protocol.Peer
import com.comcast.ip4s._
import cats.instances.option
import fs2.io.udp.SocketGroup
import kademlia.KBucket.Cache
import kademlia.types.{Contact, Node, NodeId, Prefix}
import io.estatico.newtype.macros.newtype

import fs2._
import cats.syntax.order._

trait DHT {
  //
  //def node(t:String, port:Int)
  def bootstrap()
  //def get(key)
  //def put(key)
  //def stop()
}

object DHT {


  final case class Table(nodeId:NodeId, buckets:Ref[IO, NonEmptyList[KBucket]]) {
    def addNode(node:Node) = {
      buckets.tryUpdate{ b =>
        //b.find()
      }
    }
    def addNodes(nodes:List[Node]):Boolean = {
      nodes.map{ v =>
        buckets.
      }
    }

  }
  object Table {


    def empty(nodeId:NodeId, ksize:Int = 8)(implicit clock: Clock): IO[Table] = {
      val prefix = Prefix(lowestNodeId)
      val nodes = Nodes(List.empty, ksize)
      val cache = Cache(Nodes(List.empty, ksize*3))
      for {
        b <- IO.fromEither(KBucket.create(prefix, nodes, cache))
        ref <- Ref.of[IO, NonEmptyList[KBucket]](NonEmptyList.of(b))
      }yield Table(nodeId, ref)

    }
  }
  def createContact(hostname: Hostname, port: Port = Port(6881).get): IO[Contact] = {
    hostname.resolve
      .map(_.map(ip => Contact(ip, port)))
      .flatMap(
        v =>
          IO.fromEither(
            v.toRight(Error.DHTError(s"$hostname can not be resolved"))
          )
      )
  }

  val transmissionbt = createContact(host"dht.transmissionbt.com")
  val bittorrent     = createContact(host"router.bittorrent.com")
  val utorrent       = createContact(host"router.utorrent.com")
  val silotis        = createContact(host"router.silotis.us")

  val nodeId = NodeId.gen()

  def bootstrap(
      sg: SocketGroup
  )(implicit c: Concurrent[IO], cs: ContextShift[IO], clock: Clock) = {
    for {
      table <- Table.empty(nodeId)
      contact <- transmissionbt
      nodes  <- Client(nodeId, contact, sg).findNodeF(nodeId).map(_.nodes)
    } yield nodes


    def sortNodes(nodeId:NodeId, nodes: List[Node]):List[Node] = {
      nodes.sortWith{(a, b) =>
        val f = nodeId ^ a.nodeId
        val s = nodeId ^ b.nodeId
        f > s
      }
    }
    def go(table:Table, nodes: List[Node]):IO[Table] = {
      val result = Stream.emits(nodes).take(3).map{n =>
        Client(nodeId, n.contact, sg).findNode(table.nodeId).map(_.nodes)
      }.parJoin(3).compile.toList.map(_.flatten)

      for {
        n <- result
        sorted =  sortNodes(table.nodeId, n)
        updated = table.addNodes(n)
        rr <- if(updated) go(table,sorted) else IO(table)
      }yield rr

    }

  }
}
