package biton.dht

import benc.{ BDecoder, BEncoder, BencError }
import com.comcast.ip4s.{ IpAddress, Port }
import scodec.bits.{ BitVector, ByteVector }
import scodec.codecs._
import scodec.{ Attempt, Codec, Err }

trait Codecs {

  implicit val portScodec: Codec[Port] = uint16.exmap(
    i => Attempt.fromOption(Port(i), Err(s"invalid port $i")),
    p => Attempt.successful(p.value)
  )

  implicit val portBEncoder: BEncoder[Port] =
    BEncoder.intBEncoder.contramap(_.value)
  implicit val portBDecoder: BDecoder[Port] = BDecoder.intBDecoder.emap(
    i => Port(i).toRight(BencError.CodecError(s"invalid port $i"))
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

  implicit val ipAddressBEncoder: BEncoder[IpAddress] =
    BEncoder.bitVectorBEncoder.contramap(ip => BitVector(ip.toBytes))
  implicit val ipAddressBDecoder: BDecoder[IpAddress] =
    BDecoder.bitVectorBDecoder.emap(
      bits =>
        IpAddress
          .fromBytes(bits.toByteArray)
          .toRight(BencError.CodecError(s"$bits can not convert to IpAddress"))
    )

}
