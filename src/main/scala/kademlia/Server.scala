package kademlia

import java.net.InetSocketAddress

import cats.effect.{ Concurrent, ContextShift, IO }
import com.comcast.ip4s.Port
import fs2.Stream
import fs2.io.udp.SocketGroup
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import kademlia.protocol._
import cats.syntax.option._

object Server {
  val logger = Slf4jLogger.getLogger[IO]
  def start(
      sg: SocketGroup,
      port: Port
  )(implicit c: Concurrent[IO], cs: ContextShift[IO]): Stream[IO, Unit] = {
    Stream.eval_(logger.info(s"Starting server on port $port")) ++
      KMessageSocket
        .createSocket(sg, port.some)
        .flatMap { s =>
          s.read.evalMap {
            case (remote, KMessage.Ping(t, id)) =>
              logger.debug("ping")
            case (remote, KMessage.FindNode(t, id, target)) =>
              logger.debug("FindNode")
            case (remote, KMessage.GetPeers(t, id, infohash)) =>
              logger.debug("GetPeers")
            case (
                remote,
                KMessage.AnnouncePeer(t, impliedPort, id, infoHash, port, token)
                ) =>
              logger.debug("AnnouncePeer")
            case _ =>
              logger.debug("unsupported")
          }
        }
  }
}
