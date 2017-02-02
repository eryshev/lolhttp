package lol.http

import fs2.{ Stream }
import fs2.text.{ lines, utf8Decode, utf8Encode }

import scala.concurrent.{ ExecutionContext }
import ExecutionContext.Implicits.global

class ConnectionUpgradeTests extends Tests {

  val App: Service = {
    case GET at "/" =>
      Ok("Home")
    case request @ GET at "/echo" =>
      request.headers.get(Headers.Upgrade) match {
        case Some(h"ReverseEcho") =>
          SwitchingProtocol(h"ReverseEcho", {
            _ through utf8Decode through lines map (msg => s"${msg.reverse}\n") through utf8Encode
          })
        case _ =>
          UpgradeRequired(h"ReverseEcho")
      }
    case request @ GET at "/push" =>
      request.headers.get(Headers.Upgrade) match {
        case Some(h"Push") =>
          SwitchingProtocol(h"Push", { _ =>
            Stream(1 to 1024: _*) map(_.toString + "\n") through utf8Encode
          })
        case _ =>
          UpgradeRequired(h"Push")
      }
    case _ =>
      NotFound
  }

  test("Upgrade connection") {
    withServer(Server.listen()(App)) { server =>
      val url = s"http://localhost:${server.port}/echo"

      await() {
        Client.run(Get(url).addHeaders(Headers.Upgrade -> h"ReverseEcho")) { response =>
          response.status should be (101)
          response.headers.get(Headers.Upgrade) should be (Some(h"ReverseEcho"))

          val upstream = Stream("Hello", " world\nlol", "\n", "wat??", "\n", "DONE").pure through utf8Encode
          val downstream = response.upgradeConnection(upstream) through utf8Decode through lines

          downstream.runLog.unsafeRunAsyncFuture()
        }
      } should contain inOrderOnly (
        "dlrow olleH",
        "lol",
        "??taw",
        "ENOD",
        "" // because of the way `text.lines` works we get this final chunk
      )
    }
  }

  test("Server push directly") {
    withServer(Server.listen()(App)) { server =>
      val url = s"http://localhost:${server.port}/push"

      await() {
        Client("localhost", server.port).runAndStop { client =>
          for {
            result <- client.run(Get(url).addHeaders(Headers.Upgrade -> h"Push")) { response =>
              Thread.sleep(250)
              (response.upgradeConnection(Stream.empty) through utf8Decode through lines).
                runLog.unsafeRunAsyncFuture()
            }
            _ = eventually(client.nbConnections should be (0))
          } yield result
        }
      } should be (((1 to 1024 map (_.toString)) ++ Seq("")).toVector)
    }
  }

  test("Read content twice") {
    withServer(Server.listen()(App)) { server =>
      val url = s"http://localhost:${server.port}/echo"

      await() {
        Client.run(Get(url).addHeaders(Headers.Upgrade -> h"ReverseEcho")) { response =>
          response.status should be (101)
          response.headers.get(Headers.Upgrade) should be (Some(h"ReverseEcho"))

          val upstream = Stream("Hello", " world\nlol", "\n", "wat??", "\n", "DONE").pure through utf8Encode
          val downstream = response.upgradeConnection(upstream) through utf8Decode through lines

          downstream.runLog.unsafeRunAsyncFuture().flatMap { _ =>
            downstream.runLog.unsafeRunAsyncFuture()
          }
        }
      } shouldBe empty
    }
  }

  test("Upgrade twice") {
    withServer(Server.listen()(App)) { server =>
      val url = s"http://localhost:${server.port}/echo"

      await() {
        Client.run(Get(url).addHeaders(Headers.Upgrade -> h"ReverseEcho")) { response =>
          response.status should be (101)
          response.headers.get(Headers.Upgrade) should be (Some(h"ReverseEcho"))

          val upstream = Stream("Hello", " world\nlol", "\n", "wat??", "\n", "DONE").pure through utf8Encode
          val downstream = response.upgradeConnection(upstream) through utf8Decode through lines
          val downstream2 = response.upgradeConnection(upstream) through utf8Decode through lines

          downstream.runLog.unsafeRunAsyncFuture().flatMap { _ =>
            downstream2.runLog.unsafeRunAsyncFuture()
          }
        }
      } shouldBe empty
    }
  }

  test("Server refuse to upgrade") {
    withServer(Server.listen()(App)) { server =>
      val url = s"http://localhost:${server.port}/"

      an [Exception] should be thrownBy await() {
        Client.run(Get(url).addHeaders(Headers.Upgrade -> h"ReverseEcho")) { response =>
          response.status should not be (101)
          response.headers.get(Headers.Upgrade) should be (None)

          // Upgrade anyway :)
          val upstream = Stream.pure("lol") through utf8Encode
          val downstream = response.upgradeConnection(upstream)

          downstream.run.unsafeRunAsyncFuture()
        }
      }
    }
  }

}
