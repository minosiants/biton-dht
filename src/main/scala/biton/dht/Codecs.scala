package biton.dht

import benc.{ BCodec, BencError }
import com.comcast.ip4s.{ IpAddress, Port }
import scodec.bits.ByteVector
import scodec.codecs._
import scodec.{ Attempt, Codec, Err }
import cats.syntax.either._

trait Codecs {

  implicit val portScodec: Codec[Port] = uint16.exmap(
    i => Attempt.fromOption(Port(i), Err(s"invalid port $i")),
    p => Attempt.successful(p.value)
  )
  implicit val portBCodec: BCodec[Port] = BCodec.intBCodec.exmap(
    i => Port(i).toRight(BencError.CodecError(s"invalid port $i")),
    p => p.value.asRight
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
