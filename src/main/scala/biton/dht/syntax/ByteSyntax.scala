package biton.dht.syntax

trait ByteSyntax {
  implicit def byteSyntax(b: Byte): ByteOps = new ByteOps(b)
}

final class ByteOps(val b: Byte) extends AnyVal {
  def ubyte: Int = b & 0xFF
}
