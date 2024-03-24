package io.foldables.conductor

import smithy4s.{Hints, ShapeId}

/**
 * A generic endpoint info.
 * Sometimes, when we can't present a precise compile-time endpoint info -
 * we fallback to runtime value with shape ids and hints.
 * This for example could be used to store permissions in database
 *
 * @param service id of the service, that this endpoint belongs to
 * @param id of the endpoint (operation) itself
 * @param hints Smithy traits of the endpoints
 */
final case class EndpointInfo(service: ShapeId, id: ShapeId, hints: Hints):
  /** Id of the form `tasks.GetTask` */
  def operationId: String =
    s"${service.name}.${id.name}"
