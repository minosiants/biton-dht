package biton.dht

import biton.dht.Conf.{ GoodDuration, SecretExpiration }
import biton.dht.protocol.KMessage
import cats.effect.{ Blocker, IO }
import com.comcast.ip4s.{ IpAddress, Port }
import fs2._
import fs2.io.udp.SocketGroup
import types.{ Contact, Node }

import scala.concurrent.duration._

class ServerSpec extends KSuite {
  import ServerSpec._

  val serverNode = (for {
    id   <- nodeIdCharGen.sample
    ip   <- IpAddress("127.0.0.1")
    port <- Port(2222)
  } yield Node(id, Contact(ip, port))).get

  val clientNodeId = nodeIdCharGen.sample.get
  def sendToServer[A](f: Client => Stream[IO, A]): IO[A] =
    Blocker[IO]
      .use { blocker =>
        SocketGroup[IO](blocker).use { sg =>
          val s = for {
            pingClient <- PingClient()
            secrets    <- Secrets.create(SecretExpiration(1.minute))
            table <- TableState.empty(
              serverNode.nodeId,
              pingClient,
              GoodDuration(1.minute)
            )
            store <- PeerStore.inmemory()
            server = Server(
              serverNode.nodeId,
              table,
              store,
              secrets,
              sg,
              serverNode.contact.port
            )
          } yield server.start()
          val c =
            f(Client(clientNodeId, sg))
          c.concurrently(Stream.eval_(s)).compile.toList.map(_.head)
        }
      }

  test("ping") {
    val result = sendToServer(_.ping(serverNode)).attempt.unsafeRunSync()
    result.isRight
  }

  test("findNode") {
    val result =
      sendToServer(_.findNode(serverNode.contact, serverNode.nodeId)).attempt
        .unsafeRunSync()
    result.isRight
  }

}
object ServerSpec {
  final case class PingClient() extends Client.Ping {
    override def ping(node: Node): Stream[IO, KMessage.NodeIdResponse] = ???
  }
  object PingClient {
    def apply(): IO[PingClient] = ???
  }
}
