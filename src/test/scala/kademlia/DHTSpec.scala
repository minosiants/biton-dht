package kademlia

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{ Files, Path }

import cats.effect._
import cats.syntax.either._
import cats.syntax.show._
import fs2.io.udp.SocketGroup
import fs2.Stream
import boopickle.Default._
import kademlia.types.{ NodeId, Prefix }
import scodec.bits.BitVector
import java.io.{
  FileInputStream,
  FileOutputStream,
  ObjectInputStream,
  ObjectOutputStream
}

class DHTSpec extends KSuite with TableFunctions {
  test("bootstrap") {

    val bs = Blocker[IO]
      .use { blocker =>
        SocketGroup[IO](blocker).use { sg =>
          DHT.bootstrap(sg).table
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

  test("load".ignore) {
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
    val tableRes = objectInputStream(
      Path.of("e1d4601dcde6f662bc6528a2ea54f8463fbe0951.kad")
    ).use { o =>
      IO(o.readObject().asInstanceOf[KTable])
    }
    val res = (for {
      table <- tableRes
      _ = println(table)
    } yield table).attempt.unsafeRunSync().toOption.get
    println(res.nodeId.value.toBin)
    res.kbuckets.toVector.foreach { v =>
      println(formatBucket(v))
    }

    println(res.kbuckets.map(_.from.value).toVector.mkString("\n"))

  }

}
