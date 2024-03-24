package io.foldables.conductor

import cats.Monad
import cats.syntax.all.*

import smithy4s.ShapeId

/**
 * A `function` tied to a specific operation (endpoint) of a service
 * Based on the input `I_` of the `function` it will decide if request is allowed to proceed
 * to the actual implementation or an error should be raised
 */
final case class EndpointHandler[F[_]: Monad, A, I, E, O](id: ShapeId,
                                                          function: Option[(I, A) => F[AuthResult[E]]],
                                                          transformFunction: Option[(I, A, O) => F[O]] = None) extends EndpointHandler.Ops[F, A, I, E, O, ?, ?]:
  import EndpointHandler.*

  def authorizeWithInput(f: (I, A) => F[AuthResult[E]]): EndpointHandler[F, A, I, E, O] =
    copy(function = Some(f))

  def authorizeWithInputPure(f: (I, A) => AuthResult[E]): EndpointHandler[F, A, I, E, O] =
    copy(function = Some((i: I, a: A) => Monad[F].pure(f(i, a))))

  def authorize(f: A => F[AuthResult[E]]): EndpointHandler[F, A, I, E, O] =
    copy(function = Some((i: I, a: A) => f(a)))

  def authorizePure(f: A => AuthResult[E]): EndpointHandler[F, A, I, E, O] =
    copy(function = Some((i: I, a: A) => Monad[F].pure(f(a))))

  def transform(f: (I, A, O) => F[O]): EndpointHandler[F, A, I, E, O] =
    copy(transformFunction = Some(f))

  private[conductor] def run(getAuth: F[A],
                             original: F[O],
                             callback: Option[A => F[Unit]],
                             input: I)
                            (implicit F: Monad[F]): F[O] =
    for
      auth <- getAuth
      out <- function match
        case Some(authorize) =>
          for
            checkResult <- authorize(input, auth)
            output <- checkResult match
              case AuthResult.Allow =>
                original.flatMap(o => transformFunction.fold(Monad[F].pure(o))(f => f.apply(input, auth, o)))
              case f: AuthResult.Forbid[?] =>
                f.raise[F, O]
          yield output
        case None =>
          for
            output <- original
            result <- transformFunction.fold(Monad[F].pure(output))(f => f.apply(input, auth, output))
          yield result
      _ <- callback.fire.apply(auth)
    yield out


object EndpointHandler:
  extension [F[_]: Monad, A](original: Option[A => F[Unit]])
    def fire: A => F[Unit] =
      original.getOrElse((auth: A) => Monad[F].unit)

  /** Counterpart of `EndpointHandler.run`, when there's no handler defined for an endpoint */
  private[conductor] def runDefault[F[_] : Monad, A, O](getAuth: F[A],
                                                        out: F[O],
                                                        callback: Option[A => F[Unit]],
                                                        info: EndpointInfo,
                                                        default: Option[(EndpointInfo, A) => F[AuthResult[?]]]): F[O] =
    default match
      case Some(authenticate) =>
        for
          auth <- getAuth
          authResult <- authenticate(info, auth)
          _ <- callback.fire.apply(auth)
          result <- authResult match
            case AuthResult.Allow =>
              out
            case f: AuthResult.Forbid[?] =>
              f.raise[F, O]
        yield result
      case None =>
        callback match
          case Some(cb) => out <* getAuth.flatMap(cb)
          case None => out

  sealed trait Ops[F[_], A, I, E, O, SI, SO]:
    /** Check both input `I` and auth `A`. And perform side-effect */
    def authorizeWithInput(f: (I, A) => F[AuthResult[E]]): EndpointHandler[F, A, I, E, O]

    /** Check both input `I` and auth `A`. But no side-effect */
    def authorizeWithInputPure(f: (I, A) => AuthResult[E]): EndpointHandler[F, A, I, E, O]

    /** Check only auth */
    def authorize(f: A => F[AuthResult[E]]): EndpointHandler[F, A, I, E, O]

    /** Check only auth, without even side-effects */
    def authorizePure(f: A => AuthResult[E]): EndpointHandler[F, A, I, E, O]

    /** Post-evaluate transformation of the output */
    def transform(f: (I, A, O) => F[O]): EndpointHandler[F, A, I, E, O]


  object Ops:

    type Of[F[_], A] = [I, E, O, SI, SO] =>> Ops[F, A, I, E, O, SI, SO]

    def apply[F[_]: Monad, A, I, E, O, SI, SO](id: ShapeId): Ops[F, A, I, E, O, SI, SO] =
      new Ops[F, A, I, E, O, SI, SO]:
        // Authentication checks
        def authorizeWithInput(f: (I, A) => F[AuthResult[E]]): EndpointHandler[F, A, I, E, O] =
          EndpointHandler(id, Some(f))

        def authorizeWithInputPure(f: (I, A) => AuthResult[E]): EndpointHandler[F, A, I, E, O] =
          EndpointHandler(id, Some((i: I, a: A) => Monad[F].pure(f(i, a))))

        def authorize(f: A => F[AuthResult[E]]): EndpointHandler[F, A, I, E, O] =
          EndpointHandler(id, Some((i: I, a: A) => f(a)))

        def authorizePure(f: A => AuthResult[E]): EndpointHandler[F, A, I, E, O] =
          EndpointHandler(id, Some((i: I, a: A) => Monad[F].pure(f(a))))

        def transform(f: (I, A, O) => F[O]): EndpointHandler[F, A, I, E, O] =
          EndpointHandler(id, None, Some(f))
