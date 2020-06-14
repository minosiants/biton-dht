package biton.dht
import biton.dht.Conf.SecretExpiration

import scala.concurrent.duration._
import cats.syntax.eq._
import fs2.Stream
class SecretsSpec extends KSuite {

  test("get the same secret") {

    val res = for {
      secrets <- Secrets.create(SecretExpiration(1.second))
      res <- Stream
        .emit(secrets)
        .concurrently(secrets.refresh())
        .evalMap { s =>
          for {
            s1 <- s.get
            s2 <- s.get
          } yield s1 === s2
        }
        .take(1)
        .compile
        .toList
    } yield res.head

    assert(res.unsafeRunSync())

  }
  test("get different secret") {
    val res = for {
      secrets <- Secrets.create(SecretExpiration(100.millis))
      res <- Stream
        .emit(secrets)
        .concurrently(secrets.refresh())
        .evalMap { s =>
          for {
            s1 <- s.get
            _  <- ioTimer.sleep(110.millis)
            s2 <- s.get
          } yield s1 =!= s2
        }
        .take(1)
        .compile
        .toList
    } yield res.head

    assert(res.unsafeRunSync())
  }

}
