package io.foldables.conductor

import smithy4s.Smithy4sThrowable

enum AuthResult[+E]:
  case Allow extends AuthResult[Nothing]
  case Forbid private[conductor](error: E) extends AuthResult[E]

object AuthResult:
  def allow: AuthResult[Nothing] =
    AuthResult.Allow

  def forbid[E <: Smithy4sThrowable](error: E): AuthResult[E] =
    AuthResult.Forbid(error)

  extension [E] (forbid: AuthResult.Forbid[E])
    def raise[F[_], O]: F[O] =
      forbid match
        case AuthResult.Forbid(t: Throwable) =>
          throw t
        case AuthResult.Forbid(x) =>
          throw new IllegalArgumentException(s"Forbidden to access the endpoint, but invalid error type provided, $x")
