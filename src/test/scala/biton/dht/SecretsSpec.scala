package biton.dht
import scala.concurrent.duration._
import cats.syntax.eq._

class SecretsSpec extends KSuite {

  test("get the same secret") {
    Secrets.create(1.second).use { secrets =>
      for {
        s1 <- secrets.get
        s2 <- secrets.get
      } yield s1 === s2
    }
  }
  test("get different secret") {
    Secrets.create(100.millis).use { secrets =>
      for {
        s1 <- secrets.get
        _  <- ioTimer.sleep(110.millis)
        s2 <- secrets.get
      } yield s1 =!= s2
    }
  }
 
}
