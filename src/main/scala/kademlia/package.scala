import scodec.bits.BitVector

package object kademlia extends Codecs {

  type Result[A] = Either[Error, A]

  val idLength                 = 20 * 8
  val highestNodeId: BitVector = BitVector.high(idLength)
  val lowestNodeId: BitVector  = BitVector.low(idLength)

}
