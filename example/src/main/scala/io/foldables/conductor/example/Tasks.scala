package io.foldables.conductor.example

import org.typelevel.ci.*

import cats.effect.{Clock, IO, Ref, Resource}

import smithy4s.ShapeId

import org.http4s.{HttpRoutes, Request}

import io.foldables.conductor.{Auth, AuthResult, EndpointInfo}
import io.foldables.conductor.example.Database.User.Authentication
import io.foldables.conductor.example.generated.*

// `getAuth` is here only for demonstration purposes
// if you don't need the authentication info in your methods - you can make `Tasks` a simple object
// and use `ofService` constructor method
final case class Tasks(database: Ref[IO, Database], getAuth: IO[Authentication]) extends TasksService[IO]:

  def getTasks(): IO[GetTasksOutput] =
    database
      .get
      .map(db => GetTasksOutput(db.tasks))

  def getTask(id: TaskId): IO[GetTaskOutput] =
    database
      .get
      .map(db => db.tasks.find(task => task.id == id))
      .map:
        case Some(task) => GetTaskOutput(task)
        case None => throw NotFound(s"Task with $id id not found")

  def deleteTask(id: TaskId): IO[DeleteTaskOutput] =
    database
      .modify: db =>
        val updated = db.tasks.filter(task => task.id != id)
        (db.copy(tasks = updated), DeleteTaskOutput(updated != db.tasks))

  def postTask(body: TaskNew): IO[PostTaskOutput] =
    for
      user <- getAuthUser
      id <- database.get.map(db => TaskId(db.tasks.length + 1))
      now <- Clock[IO].realTimeInstant.map(_.toString)
      task = TaskWithMeta(id, now, user.id, Task(body.title, TaskStatus.undone, body.assigneeId))
      _ <- database.update(db => db.copy(tasks = task :: db.tasks))
    yield PostTaskOutput(task)

  def putTask(id: TaskId, body: Task): IO[Unit] =
    for
      user <- getAuthUser
      now <- Clock[IO].realTimeInstant.map(_.toString)
      task = TaskWithMeta(id, now, user.id, Task(body.title, TaskStatus.undone, body.assigneeId))
      _ <- database.update: db =>
        db.tasks.find(_.id == id) match
          case Some(task: TaskWithMeta) =>
            val updated = db.tasks.map(t => if t.id == id then task.copy(origin = body) else t)
            db.copy(tasks = updated)
          case None =>
            throw Unauthorized("Not found actually")
    yield ()

  private def getAuthUser: IO[Database.User] =
    getAuth.flatMap:
      case Authentication.Authenticated(user) =>
        IO.pure(user)
      case Authentication.Anonymous =>
        IO.raiseError(Unauthorized("Only authenticated users can perform this operation"))


object Tasks:

  def mk(database: Ref[IO, Database])(getAuth: IO[Authentication]): Tasks =
    Tasks(database, getAuth)

  def getTasksTransform(input: Unit, auth: Authentication, out: GetTasksOutput) =
    auth match
      case Authentication.Authenticated(_) =>
        IO.pure(out)
      case Authentication.Anonymous =>
        val erased = out.body.map(task => task.copy(origin = task.origin.copy(assigneeId = None)))
        IO.pure(GetTasksOutput(erased))

  def getTaskAuthorize(auth: Authentication) =
    auth match
      case Authentication.Anonymous =>
        AuthResult.forbid(Unauthorized("Anonymous user cannot see task details!"))
      case Authentication.Authenticated(Database.User(_, "admin")) =>
        AuthResult.allow
      case Authentication.Authenticated(_) =>
        AuthResult.forbid(Unauthorized(s"Non-admin user cannot post new tasks"))

  def deleteTaskAuthorize(db: Ref[IO, Database])(input: TaskIdInput, auth: Authentication): IO[AuthResult[TasksServiceOperation.DeleteTaskError]] =
    auth match
      case Authentication.Anonymous =>
        IO.pure(AuthResult.forbid(Unauthorized(s"Anonymous user cannot delete tasks (in fact we don't know if there's such task with id ${input.id})")))
      case Authentication.Authenticated(Database.User(userId, _)) =>
        db.get.map(_.tasks.find(_.id == input.id)).map:
          case Some(task) if task.createdBy == userId =>
            AuthResult.allow
          case Some(task) =>
            AuthResult.forbid(Unauthorized(s"Only author of the task (which is ${task.createdBy} in this case) can delete the task"))
          case None =>
            AuthResult.forbid(NotFound("No such task"))

  def defaultAuthorize(info: EndpointInfo, auth: Authentication): IO[AuthResult[?]] =
    info match
      case EndpointInfo(ShapeId(_, service), ShapeId(_, name), _) =>
        IO.println(s"Allow $name of $service") *> IO.pure(AuthResult.allow)

  /** Try to find a session token in the database and attach session's user */
  def authenticate(db: Ref[IO, Database])(req: Request[IO]): IO[Authentication] =
    req.headers.get(ci"Authorization") match
      case Some(headers) =>
        val sessionToken = headers.head.value
        db
          .get
          .map: database =>
            val user = database
              .sessions
              .find(s => s.token.toString == sessionToken)
              .flatMap(s => database.users.find(user => user.id == s.userId))
            user match
              case None => Authentication.Anonymous
              case Some(u) => Authentication.Authenticated(u)
      case None =>
        IO.pure(Authentication.Anonymous)


  val mkRoutes: Resource[IO, HttpRoutes[IO]] =
    Database.create[IO].flatMap: db =>
      val auth = Auth[IO, Authentication](authenticate(db), Authentication.Anonymous)

      auth
        .ofServiceConstructor(Tasks.mk(db), TasksServiceGen.serviceProduct)
        .withUnsafeDefault(defaultAuthorize)
        .withHandler(_.getTasks   .transform(getTasksTransform))
        .withHandler(_.getTask    .authorizePure(getTaskAuthorize))
        .withHandler(_.deleteTask .authorizeWithInput(deleteTaskAuthorize(db)))
        .build
