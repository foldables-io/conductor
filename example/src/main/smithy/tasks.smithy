$version: "2.0"

metadata smithy4sErrorsAsScala3Unions = true

namespace io.foldables.conductor.example.generated

use smithy4s.meta#generateServiceProduct
use alloy#discriminated
use alloy#simpleRestJson


@generateServiceProduct
@simpleRestJson
@httpBearerAuth
service TasksService {
    operations: [
        GetTask,
        GetTasks,
        PostTask,
        PutTask,
        DeleteTask
    ]
}

integer TaskId

string ActorId

structure TaskIdInput {
    @httpLabel
    @required
    id: TaskId
}


@readonly
@auth([httpBearerAuth])
@http(method: "GET", uri: "/api/v1/tasks/get/{id}", code: 200)
operation GetTask {
    input: TaskIdInput
    output: GetTaskOutput
    errors: [ Unauthorized, NotFound ]
}

structure GetTaskOutput {
    @httpPayload
    @required
    body: TaskWithMeta
}


@readonly
@auth([httpBearerAuth])
@http(method: "GET", uri: "/api/v1/tasks", code: 200)
operation GetTasks {
    input: Unit
    output: GetTasksOutput
    errors: [ Unauthorized ]
}

structure GetTasksOutput {
    @httpPayload
    @required
    body: TasksCollection
}


@idempotent
@auth([httpBearerAuth])
@http(method: "DELETE", uri: "/api/v1/tasks/{id}", code: 200)
operation DeleteTask {
    input: TaskIdInput
    output: DeleteTaskOutput
    errors: [ Unauthorized, NotFound ]
}

@output
structure DeleteTaskOutput {
    @httpPayload
    @required
    body: Boolean
}


@auth([httpBearerAuth])
@http(method: "POST" uri: "/api/v1/tasks" code: 200)
operation PostTask {
    input: PostTasksInput
    output: PostTaskOutput
    errors: [ Unauthorized ]
}

structure PostTasksInput {
    @httpPayload
    @required
    body: TaskNew
}

@output
structure PostTaskOutput {
    @httpPayload
    @required
    body: TaskWithMeta
}


@auth([httpBearerAuth])
@http( method: "PUT", uri: "/api/v1/tasks/{id}", code: 200)
operation PutTask {
    input: PutTaskInput
    output: Unit
    errors: [ Unauthorized, NotFound ]
}

structure PutTaskInput {
    @httpLabel
    @required
    id: TaskId

    @httpPayload
    @required
    body: Task
}


@error("client")
@httpError(401)
structure Unauthorized {
    @required
    message: String
}

@error("client")
@httpError(404)
structure NotFound {
    @required
    message: String
}

structure Task {
    @required
    title: String

    assigneeId: ActorId

    @required
    status: TaskStatus
}

structure TaskNew {
    @required
    title: String

    assigneeId: ActorId
}

structure TaskWithMeta {
    @required
    id: TaskId

    @required
    createdAt: String

    @required
    createdBy: ActorId

    @required
    origin: Task
}

list TasksCollection {
    member: TaskWithMeta
}

enum TaskStatus {
    done
    on_hold = "on-hold"
    undone
}
