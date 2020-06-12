package biton.dht

import java.nio.file.{ Path, Files => JFiles }

import cats.effect.IO
import scodec.bits.BitVector

trait BytesConverter[A] {
  def to(a: A): Array[Byte]
  def from(array: Array[Byte]): A
}

object BytesConverter {
  def apply[A](implicit bc: BytesConverter[A]): BytesConverter[A] = bc

  def instance[A](
      fto: A => Array[Byte],
      ffrom: Array[Byte] => A
  ): BytesConverter[A] = new BytesConverter[A] {
    override def to(a: A): Array[Byte]       = fto(a)
    override def from(array: Array[Byte]): A = ffrom(array)
  }

  implicit val bitvectorBytesConverter: BytesConverter[BitVector] =
    BytesConverter.instance(_.toByteArray, BitVector(_))
  implicit val rawBytesConverter: BytesConverter[Array[Byte]] =
    BytesConverter.instance(identity, identity)
}

object Files {

  private def exec[A](f: => A)(msg: String): IO[A] =
    IO {
      f
    }.handleErrorWith { e: Throwable =>
      IO.raiseError(Error.FileOpsError(msg, e))
    }

  def delete(path: Path): IO[Boolean] =
    exec(JFiles.deleteIfExists(path))(s"Unable to delete file: $path")

  def write[A: BytesConverter](path: Path, a: A): IO[Path] =
    exec {
      JFiles.createDirectories(path.getParent)
      JFiles.write(path, BytesConverter[A].to(a))
    }(s"Unable write to file: $path")

  def read[A: BytesConverter](path: Path): IO[A] =
    exec {
      BytesConverter[A].from(JFiles.readAllBytes(path))
    }(s"Unable read from file: $path")

}
