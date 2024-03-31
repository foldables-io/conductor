# Smithy4s Conductor

Conductor is a companion library to [smithy4s](https://github.com/disneystreaming/smithy4s) providing a type-safe and
boilerplate-free API to add authentication and authorization logic to your smithy4s-generated services.

In particular, its API enables to:

- Define authentication logic in one place as a `Request[F] => F[A]` function, and seamlessly reuse it across multiple services.
  The logic could be something like "look up for `Authorization` header and find out what `UserId` it matches in a Redis instance"
- Authorize above `A` (which becomes `Option[UserId]` in our example) in a very customizable way to perform certain operation.
  Think of "allow a user to perform `DeleteTask` operation if the user is an author of the task"
- Perform some post-processing of the operation output, for example if you need to erase some information in `O` when
  user doesn't have permission to view it
- Invoke callbacks, for example to implement event-driven architecture or track user activity

The above use cases probably remind you of typical [HTTP Middleware](https://http4s.org/v0.21/middleware/) and Conductor is middleware in some sense,
but given that it's attached on per-endpoint basis - it's completely aligned with types of your endpoint, `O` and `E` represent output
and error exactly of that endpoint. And very much like with middleware - this logic is detached from logic of your service implementation.

## Prerequisites

- It's Scala 3 only
- All services you want to use this library for must have `generateServiceProduct` trait
- Your Smithy definition must have `metadata smithy4sErrorsAsScala3Unions = true`

## Example

You can see the whole code in [example directory](example).

### Intro

In nutshell it's a service with the definition similar to the following:

```scala
object Tasks extends TasksService[IO]:
  def getTasks(id: TaskId): IO[GetTaskOutput] = ???
  def postTask(body: TaskBase): IO[PostTaskOutput] = ???
  def deleteTask(id: TaskId): IO[DeleteTaskOutput] = ???
```

Now, imagine this simple service needs to have the following logic:

- Anyone can perform `GetTasks` operation, but if user is anonymous they cannot see the assignee of any of the tasks (we erase `assigneeId` field)
- Only admins can perform `PostTask`
- Only authors of the task can perform `DeleteTask`

### The Challenge

The first problem arising here is that we don't have any notion of authentication - every operation could be called by
admin as well as anonymous user. Smithy4s helps you to solve this problem with
[Server-side middleware](https://disneystreaming.github.io/smithy4s/docs/guides/endpoint-middleware#server-side-middleware),
but despite being a very generic mechanism it has its own drawbacks:

1. If authentication is successful - all we know that the operation can be performed, but all information about _who_ performs the request is lost
2. It's attached to the whole service and it's very hard to apply it on per-endpoint basis

Addressing the first issue typically involves leveraging [IOLocal](https://disneystreaming.github.io/smithy4s/docs/guides/extract-request-info)
alongside middleware, a solution that, while effective, tends to require a substantial amount of boilerplate code.
So, Conductor encapsulates most of that boilerplate (yes, Conductor is nothing more than `IOLocal` plus Middleware wrapped in a nice fluent API).

### Authentication and Handlers

First you define your own `Authentication` domain (see [`Database`](example/src/main/scala/io/foldables/conductor/example/Database.scala)):

```scala
enum Authentication:
  case Authenticated(user: User)
  case Anonymous
```

This information will be derived for every incoming HTTP Request via so called `Auth` logic:

```scala
val auth = Auth[IO, Authentication](authenticate(db), Authentication.Anonymous)
```

The next stage is to define so called "handlers" - functions that process authorize the input and transform the output.

```scala
def getTasksTransform(input: Unit, auth: Authentication, out: GetTasksOutput) =
  auth match
    case Authentication.Authenticated(_) =>
      IO.pure(out)
    case Authentication.Anonymous =>
      val erased = out.body.map(task => task.copy(origin = task.origin.copy(assigneeId = None)))
      IO.pure(GetTasksOutput(erased))
```

In this transformer:

- First argument is an input of the endpoint. Empty in this case.
- Second argument is our domain-specific `Authetntication`
- Third argument is the endpoint output, that got evaluated and which we can modify
- The output is of the same type as third argument, but potentially a) changed; b) having some side-effects (so getting back to DB is possible)

Note, we call this handler a transformer - it will get executed only *after* the operation succeeds.
Although it's more often to use handlers that executed *before* the request even reaches the endpoint logic to perform authorization.

Here's an example of authorization handler:

```scala
def getTaskAuthorize(auth: Authentication) =
  auth match
    case Authentication.Anonymous =>
      AuthResult.forbid(Unauthorized("Anonymous user cannot see task details!"))
    case Authentication.Authenticated(Database.User(_, "admin")) =>
      AuthResult.allow
    case Authentication.Authenticated(_) =>
      AuthResult.forbid(Unauthorized(s"Non-admin user cannot post new tasks"))
```

This particular handler:

- Uses only authentication info in order to make the decision (although using input is also possible)
- Doesn't perform any side-effects (although they're also possible)
- Type-safe! You use only `AuthResult[E]` to make the decision and `E` *must* match the error type of the endpoint

Here's another, more complicated authorization handler:

```scala
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
```

In this case:

- We perform IO to access the database - note, you're responsible for caching mechanism, Conductor doesn't attempt to reduce IO
- We have both input and authentication info available. We can use the input to a) give more precise errors; b) to check something in database prior executing the operation
- Despite having two independent error (`Unauthorized` and `NotFound`) - it's still type-safe (this is where `smithy4sErrorsAsScala3Unions` used)

This is all cool and type-safe, but what if we want to define a dynamic authorization for our endpoint.
For example, with above logic an anonymous user will never be able to perform `GetTask` without recompiling the whole service. What if we want to store and change that information in database?
In this case we can use the "default authorization" logic, which is a function of following form:

```scala
def defaultAuthorize(info: EndpointInfo, auth: Authentication): IO[AuthResult[?]] =
  info match
    case info @ EndpointInfo(ShapeId(_, service), ShapeId(_, name), _) =>
      Database.isAllowed(auth.id, info.operationId)   // pseudo-code
```

In this case `EndpointInfo` is just a `ShapeId` and `Hints` of an operation being performed. And we also know who attempts to perform that operation.
And finally, we can perform an arbitrary IO with that information.

Note: you need to be super-careful with errors you're throwing here. Because unlike per-endpoint handlers, this one is not type-safe because
we cannot enforce the error type when endpoint is not statically known.

Note: default authorization handler runs only when there's no specific endpoint, so it's OR relationship rather than AND.

### Tying it all together

We defined:

- Authentication domain and logic
- A transformer
- A couple of authorization handlers
- The Default Authorization handler

Now it's time to attach it to our service

```scala
val mkRoutes: Resource[IO, HttpRoutes[IO]] =
  auth
    .ofService(Tasks, TasksServiceGen.serviceProduct)
    .withUnsafeDefault(defaultAuthorize)
    .withHandler(_.getTasks   .transform(getTasksTransform))
    .withHandler(_.getTask    .authorizePure(getTaskAuthorize))
    .withHandler(_.deleteTask .authorizeWithInput(deleteTaskAuthorize(db)))
    .build
```

We start with our own `auth` and attach it to `Tasks` service. `TasksServiceGen.serviceProduct` is what comes in when you add `generateServiceProduct` trait
to your service and eventually it will become implicit, but now you need to pass it in manually in order to enable Conductor's fluent API.

- `withUnsafeDefault` attached our default authorization hanlder (it's "unsafe" because errors aren't checked)
- `withHandler` enables you to add different type-safe handlers to each of endpoints. This is where we attach most of previously defined handlers.
  - `transform` for the handler that will be executed *after* operation
  - `authorizePure` for the authorization handler that checks only auth info and performs no IO
  - `authorize` for the authorization handler that checks only auth info and can perform IO
  - `authorizeWithInputPure` for the authorization handler that checks both auth info and the input and performs no IO
  - `authorizeWithInput` for the authorization handler that checks both auth info and the input and can perform IO
- `withCallback`  adds a side-effecting fire-and-forget function that for example can put an output to some kind of log
- Finally, `build` transforms it all into `HttpRoutes`

### Using auth info inside the Service

Last, but not least - all our endpoints remain without information about who executes the operation.
Yes, we authenticated and authorized the request, but what if someone needs that info as part of the operation logic (handlers are pre- and post-operation on purpose)?
For example, in `PostTask` operation we might want to save the task with `createdBy` property stating who creates the task.

In that case, we need to add `IO[Authentication]` argument to the constructor of the service:

```scala
class Tasks(getAuth: IO[Authentication]) extends TasksService[IO]:
  def postTask(task: TaskNew): IO[PostTaskOutput] =
    for
      user <- getAuth
      output <- database.add(Task(user, task))
    yield output
```

Now, when you attach the service to your authentication domain, you need to use not `ofService` factor (which accepts ready-to use service),
but `ofServiceConstructor` factory, which accepts `IO[Authentication] => Tasks` constructor.

Bear in mind however, that it's better to keep all authorization logic either in handlers or in endpoints, but not to mix it.
If you use `getAuth` IO - make sure it's for attaching info, not for authorizing requests.

## Copyright and License

Conductor is copyright 2024 Foldables Ltd.

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this software except in compliance with the License.

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
