package io.foldables.conductor.example

import cats.effect.{ IOApp, IO }

import org.http4s.server.Router
import org.http4s.ember.server.EmberServerBuilder

import com.comcast.ip4s.*

object Main extends IOApp.Simple:
  def run: IO[Unit] =
    Tasks
      .mkRoutes
      .flatMap: routes =>
        val app = Router[IO]("/" -> routes).orNotFound
        EmberServerBuilder
          .default[IO]
          .withHost(host"0.0.0.0")
          .withPort(port"9001")
          .withHttpApp(app)
          .build
      .use(_ => IO.never)
