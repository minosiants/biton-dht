package kademlia

import java.io.{
  FileInputStream,
  FileOutputStream,
  ObjectInputStream,
  ObjectOutputStream
}
import java.nio.file.Path

import cats.effect._
import cats.syntax.either._
import cats.syntax.show._
import com.comcast.ip4s.Port
import fs2.io.udp.SocketGroup
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import kademlia.protocol.InfoHash
import kademlia.types.NodeId
import scodec.bits._

class DHTSpec extends KSuite with TableFunctions {

  test("bootstrap") {

    val bs = Blocker[IO]
      .use { blocker =>
        SocketGroup[IO](blocker).use { sg =>
          DHT.bootstrap(sg, Port(6881).get).flatMap(_.table)
        }
      }

    def saveTable(table: Table): IO[Unit] = IO {
      val fileOutputStream   = new FileOutputStream(fileName(table.nodeId))
      val objectOutputStream = new ObjectOutputStream(fileOutputStream)
      objectOutputStream.writeObject(table)
      objectOutputStream.flush()
      objectOutputStream.close()
    }

    def fileName(nodeId: NodeId): String = {
      s"${nodeId.value.toHex}.kad"
    }

    def objectOutputStream(path: Path): Resource[IO, ObjectOutputStream] = {
      Resource.make {
        IO {
          val fileOutputStream = new FileOutputStream(path.toString)
          new ObjectOutputStream(fileOutputStream)
        }
      } { o =>
        IO {
          o.flush()
          o.close()
        }
      }
    }

    val res = (for {
      table <- bs
      _     <- saveTable(table)
    } yield table).attempt.unsafeRunSync()

    res.leftMap {
      case e: Error => println(e.show)
    }
    println(res.map(_.nodeId.value.toBin))
    println("--")
    println(
      res.map(
        _.kbuckets.head.nodes.value.map(_.nodeId.value.toBin).mkString("\n")
      )
    )
    true
  }

  test("lookup".only) {
    val bits     = hex"311aeba8ecb3f5b9ba8935bf326f049e63cc5967"
    val infoHash = InfoHash(bits.bits)
    val bs = Blocker[IO]
      .use { blocker =>
        SocketGroup[IO](blocker).use { sg =>
          for {
            table <- loadTable("fffc21a3f289db8057396725b6cd53d4c0759991.kad")
            res <- DHT
              .fromTable(sg, Port(6881).get, table)
              .flatMap(_.lookup(infoHash))
          } yield res
        }
      }

    val result = bs.unsafeRunSync()
    println(result)
    true
  }

  def loadTable(name: String): IO[Table] = {
    def objectInputStream(path: Path): Resource[IO, ObjectInputStream] = {
      Resource.make {
        IO {
          val fileInputStream = new FileInputStream(path.toString)
          new ObjectInputStream(fileInputStream)
        }
      } { i =>
        IO {
          i.close()
        }
      }
    }
    objectInputStream(
      Path.of(name)
    ).use { o =>
      IO(o.readObject().asInstanceOf[KTable])
    }

  }

  test("load") {

    val res = (for {
      table <- loadTable("fffc21a3f289db8057396725b6cd53d4c0759991.kad")
      _ = println(s"nodeId ${table.nodeId.value.toHex}")
      _ = println(table)
    } yield table).attempt.unsafeRunSync().toOption.get
    println(res.nodeId.value.toBin)
    res.kbuckets.toVector.foreach { v =>
      println(formatBucket(v))
    }

    println(res.kbuckets.map(_.from.value).toVector.mkString("\n"))

  }

}

object DHTSpec {
  val logger = Slf4jLogger.getLogger[IO]
}
