package biton.dht
package syntax

import types.{ NodeId, Prefix }
import scodec.bits.BitVector

trait PrefixSyntax {
  implicit def prefixSyntax(prefix: Prefix): PrefixOps = new PrefixOps(prefix)
}

final class PrefixOps(val prefix: Prefix) extends AnyVal {
  def toNodeId: NodeId       = NodeId(BitVector(prefix.value.toByteArray))
  def +(v: Prefix): Prefix   = Prefix(prefix.value + v.value)
  def +(v: Int): Prefix      = Prefix(prefix.value + v)
  def /(n: Int): Prefix      = Prefix(prefix.value / n)
  def <(v: Prefix): Boolean  = prefix.value < v.value
  def >(v: Prefix): Boolean  = prefix.value > v.value
  def >=(v: Prefix): Boolean = prefix.value >= v.value
  def <=(v: Prefix): Boolean = prefix.value <= v.value
}
