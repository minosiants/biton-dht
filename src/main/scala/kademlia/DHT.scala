package kademlia

import java.net.InetSocketAddress

import cats.Applicative
import cats.effect.{ Concurrent, ContextShift, IO }
import kademlia.protocol.Peer
import com.comcast.ip4s._
import cats.instances.option
import fs2.io.udp.SocketGroup
import kademlia.types.NodeId
trait DHT {
  //
  //def node(t:String, port:Int)
  def bootstrap()
  //def get(key)
  //def put(key)
  //def stop()
}

object DHT {

  def createPeer(hostname: Hostname, port: Port = Port(6881).get): IO[Peer] = {
    hostname.resolve
      .map(_.map(ip => Peer(ip, port)))
      .flatMap(
        v =>
          IO.fromEither(
            v.toRight(Error.DHTError(s"$hostname can not be resolved"))
          )
      )
  }

  val transmissionbt = createPeer(host"dht.transmissionbt.com")
  val bittorrent     = createPeer(host"router.bittorrent.com")
  val utorrent       = createPeer(host"router.utorrent.com")
  val silotis        = createPeer(host"router.silotis.us")

  val nodeId = NodeId.gen()

  def bootstrap(
      sg: SocketGroup
  )(implicit c: Concurrent[IO], cs: ContextShift[IO]) = {
    for {
      peer <- transmissionbt
      res  <- Client(nodeId, peer, sg).findNodeF(???)
    } yield ???

  }
}
