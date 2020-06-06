package biton.dht

import cats.effect.{ Blocker, IO }
import com.comcast.ip4s.{ IpAddress, Port }
import fs2._
import fs2.io.udp.SocketGroup
import types.{ Contact, Node }
import scala.concurrent.duration._

class ServerSpec extends KSuite {
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
            table  <- TableState.empty(serverNode.nodeId)
            store  <- PeerStore.inmemory()
            tokens <- TokenCache.create(1.minute)
            server = Server(
              serverNode.nodeId,
              table,
              store,
              tokens,
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
