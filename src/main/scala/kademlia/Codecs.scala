package kademlia

import com.comcast.ip4s.{ IpAddress, Port }
import scodec.bits.{ BitVector, ByteVector }
import scodec.{ Attempt, Codec, Err }
import scodec.codecs._

trait Codecs {

  implicit val portScodec: Codec[Port] = uint16.exmap(
    i => Attempt.fromOption(Port(i), Err(s"invalid port $i")),
    p => Attempt.successful(p.value)
  )

  //IP6 ???
  implicit val ipAddressScocec: Codec[IpAddress] = bytes(4).exmap(
    i =>
      Attempt.fromOption(
        IpAddress.fromBytes(i.bits.toByteArray),
        Err(s"invalid ip $i")
      ),
    ip => Attempt.successful(ByteVector(ip.toBytes))
  )
}
