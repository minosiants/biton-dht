package kademlia.syntax

import kademlia.types.{ NodeId, Prefix }
import scodec.codecs.{ sizedList, uint32 }

trait PrefixSyntax {
  implicit def prefixSyntax(prefix: Prefix): PrefixOps = new PrefixOps(prefix)
}

final class PrefixOps(val prefix: Prefix) extends AnyVal {
  def toNodeId: NodeId     = NodeId(prefix.value)
  def set(n: Long): Prefix = Prefix(prefix.value.set(n))
  def >>>(n: Int): Prefix  = Prefix(prefix.value >>> n)
  def nonLow: Boolean =
    prefix.value.bytes.dropWhile(_ == 0).size != 0

  def isLow: Boolean = !nonLow
  def next: Prefix = {
    if (prefix.nonLow) prefix >>> 1 else prefix.set(0)
  }
  def toDecStr: String = {
    val d = sizedList(4, uint32)
    val result = d
      .decodeValue(prefix.value)
      .map(_.underlying.toList.mkString(""))
      .toOption
      .getOrElse("")
    result.reverse.padTo(40, '0').reverse
  }
}
