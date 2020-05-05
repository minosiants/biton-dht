package kademlia

import io.estatico.newtype.macros._
import scodec.bits.BitVector

object Types {

  @newtype final case class Key(value:BitVector)
  
}
