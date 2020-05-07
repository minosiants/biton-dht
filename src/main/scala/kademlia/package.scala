import scodec.bits.BitVector

package object kademlia {

  type Result[A] = Either[Error, A]

  val highestNodeId = BitVector.high(20 * 8)
}
