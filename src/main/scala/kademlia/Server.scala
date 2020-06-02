package kademlia

import cats.effect.{ Concurrent, ContextShift, IO }
import cats.syntax.apply._
import cats.syntax.option._
import com.comcast.ip4s.Port
import fs2.Stream
import fs2.io.udp.SocketGroup
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import kademlia.protocol.KMessage.{
  FindNodeResponse,
  NodeIdResponse,
  NodesWithPeersResponse
}
import kademlia.protocol._
import kademlia.types.NodeId

trait Server {
  def start(): Stream[IO, Unit]
}

object Server {
  val logger = Slf4jLogger.getLogger[IO]

  def apply(id: NodeId, sg: SocketGroup, port: Port)(
      implicit c: Concurrent[IO],
      cs: ContextShift[IO]
  ): Server = new Server() {

    override def start(): Stream[IO, Unit] = {

      Stream.eval_(logger.info(s"Starting server on port $port")) ++
        KMessageSocket
          .createSocket(sg, None, port.some)
          .flatMap { s =>
            s.read.evalMap {
              case (remote, KMessage.Ping(t, senderId)) =>
                logger.info("ping") *>
                  s.write1(remote, NodeIdResponse(t, id))

              case (remote, KMessage.FindNode(t, senderId, target)) =>
                logger.debug("FindNode") *>
                  s.write1(remote, FindNodeResponse(t, id, List.empty))

              case (remote, KMessage.GetPeers(t, senderId, infohash)) =>
                logger.debug("GetPeers") *>
                  s.write1(
                    remote,
                    NodesWithPeersResponse(t, id, Token.gen(), None, None)
                  )
              case (
                  remote,
                  KMessage.AnnouncePeer(
                    t,
                    impliedPort,
                    senderId,
                    infoHash,
                    port,
                    token
                  )
                  ) =>
                logger.debug("AnnouncePeer") *>
                  s.write1(remote, NodeIdResponse(t, id))
              case _ =>
                logger.debug("unsupported")
            }
          }
    }
  }
}
