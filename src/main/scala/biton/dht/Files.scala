package biton.dht

import java.nio.file.{ Path, Files => JFiles }

import cats.effect.IO
import cats.syntax.functor._
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

  def write[A: BytesConverter](path: Path, a: A): IO[Unit] =
    IO(JFiles.write(path, BytesConverter[A].to(a))).handleErrorWith {
      e: Throwable =>
        IO.raiseError(Error.FileOperation(s"Unable write to file: $path", e))
    }.void

  def read[A: BytesConverter](path: Path): IO[A] = IO {
    BytesConverter[A].from(JFiles.readAllBytes(path))
  }
}
