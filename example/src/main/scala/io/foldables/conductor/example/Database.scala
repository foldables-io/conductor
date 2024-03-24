package io.foldables.conductor.example

import cats.effect.{ Ref, Concurrent, Resource }

import io.foldables.conductor.example.generated.*

final case class Database(tasks: List[TaskWithMeta],
                          users: List[Database.User],
                          sessions: List[Database.Session])

object Database:

  opaque type SessionToken = String

  final case class User(id: ActorId, role: String)

  object User:
    enum Authentication:
      case Authenticated(user: User)
      case Anonymous

  final case class Session(token: SessionToken, userId: ActorId)

  /** Initial database has three users, two of them have an open session */
  val init = Database(
    Nil,
    List(Database.User(ActorId("alice"), "admin"), Database.User(ActorId("bob"), "user"), Database.User(ActorId("eve"), "user")),
    List(Database.Session("ab98", ActorId("alice")), Database.Session("cd34", ActorId("bob"))),
  )

  def create[F[_]: Concurrent]: Resource[F, Ref[F, Database]] =
    Resource.eval(Ref.of(init))

