package io.foldables.conductor

import cats.{Functor, Monad}
import cats.data.OptionT
import cats.implicits.*
import cats.effect.{Concurrent, IOLocal, LiftIO, Resource}

import org.http4s.{HttpRoutes, Request}

import smithy4s.{Service, ServiceProduct}
import smithy4s.kinds.FunctorAlgebra
import smithy4s.http4s.SimpleRestJsonBuilder

import io.foldables.conductor.EndpointHandler.Ops


/**
 * Logic responsible for authenticating http4s requests with arbitrary IO.
 * An instance of it could be created once and then reused across multiple services,
 * given that all services use the same authentication mechanism.
 *
 * After an `Auth` instance has been created - the next step is to wrap your service
 * with `ofService` or `ofServiceConstructor` methods to attach endpoint handlers.
 *
 * @tparam F side-effect type to retrieve the `A`
 * @tparam A app-specific type showing the authentication status
 * @param transform side-effecting logic to retrieve the `A`, typically a query to a DB instance
 * @param default default `A` to initialise the internal state
 */
case class Auth[F[_], A](transform: Request[F] => F[A], default: A):
  /**
   * Construct a fluent interface, attaching different handlers to the service endpoints
   * This is used instead of `ofService` when you need authentication info inside service implementation
   * You'd need to make your service a class and pass in its constructor:
   * `(getAuth: F[A]) => YourServiceImpl.apply(getAuth)`
   *
   * @param impl the actual constructor of your service (with `getAuth` action)
   * @param serviceProduct should be available for your service when `smithy4s.meta#generateServiceProduct`
   *                       trait is applied to your service. Then it can be found at `YourServiceImpl.serviceProduct`
   *                       Later it should be provided implicitly
   */
  def ofServiceConstructor[Alg[_[_, _, _, _, _]], Prod[_[_, _, _, _, _]]]
                          (impl: F[A] => FunctorAlgebra[Alg, F], serviceProduct: ServiceProduct.Aux[Prod, Alg])
                          (implicit service: smithy4s.Service[Alg],
                                    F: Concurrent[F],
                                    L: LiftIO[F]): Auth.Intermediate[Alg, Prod, F, A] =
    implicit val sp: ServiceProduct.Aux[Prod, Alg] = serviceProduct
    Auth.Intermediate[Alg, Prod, F, A](transform, default)(Auth.Intermediate.ServiceConstructor.Present(impl))

  /**
   * Construct a fluent interface, attaching different handlers to the service endpoints
   * Unlike `ofServiceConstructor` this assumes that all authentication and authorization
   * happens in `Auth` handlers and you don't need auth info inside service
   *
   * @param impl your actual service (already instantiated)
   * @param serviceProduct should be available for your service when `smithy4s.meta#generateServiceProduct`
   *                       trait is applied to your service. Then it can be found at `YourServiceImpl.serviceProduct`
   *                       Later it should be provided implicitly
   */
  def ofService[Alg[_[_, _, _, _, _]], Prod[_[_, _, _, _, _]]]
               (impl: FunctorAlgebra[Alg, F], serviceProduct: ServiceProduct.Aux[Prod, Alg])
               (implicit service: smithy4s.Service[Alg],
                         F: Concurrent[F],
                         L: LiftIO[F]): Auth.Intermediate[Alg, Prod, F, A] =
    implicit val sp: ServiceProduct.Aux[Prod, Alg] = serviceProduct
    Auth.Intermediate[Alg, Prod, F, A](transform, default)(Auth.Intermediate.ServiceConstructor.Absent(impl))

  def map[B](f: A => B)(implicit F: Functor[F]): Auth[F, B] =
    Auth(req => transform(req).map(f), f(default))

object Auth:
  /** An intermediate stage of authenticated service providing fluent interface with two main methods `withHandler` and `build` */
  case class Intermediate[Alg[_[_, _, _, _, _]], Prod[_[_, _, _, _, _]], F[_], A]
                         (impl: F[(FunctorAlgebra[Alg, F], F[A], Request[F] => F[Unit])],
                          serviceProduct: ServiceProduct.Aux[Prod, Alg],
                          handlers: List[Prod[Ops.Of[F, A]] => EndpointHandler[F, A, ?, ?, ?]],
                          default: Option[(EndpointInfo, A) => F[AuthResult[?]]],
                          callback: Option[(EndpointInfo, A) => F[Unit]]):
    implicit val sp: ServiceProduct.Aux[Prod, Alg] = serviceProduct

    /**
     * The main method, allowing to attach different handlers (provided by [[Ops]]
     *
     * Calling `withCheck` multiple times results in only last handler remaining attached
     *
     * @param op an individual endpoint handler
     * @return the same builder, but the handler attached
     */
    def withHandler(op: Prod[Ops.Of[F, A]] => EndpointHandler[F, A, ?, ?, ?]): Intermediate[Alg, Prod, F, A] =
      copy(handlers = op :: this.handlers)

    /**
     * Add a one-size-fits-all authentication handler, which can cover all endpoints at once
     * Note however that this handler is unsafe because nothing guarantees that the error type
     * produced in here is specified at the endpoint definition
     */
    def withUnsafeDefault(op: (EndpointInfo, A) => F[AuthResult[?]]): Intermediate[Alg, Prod, F, A] =
      copy(default = Some(op))

    /**
     * Add a simple fire-and-forget callback to all the endpoints
     * Here you can state that a user invoked an operation. You can't get neither input nor output though
     * because there's no way to get in a type-safe way. For that kind of callbacks you can use generic
     * handlers with `withHandler`
     */
    def withCallback(cb: (EndpointInfo, A) => F[Unit]): Intermediate[Alg, Prod, F, A] =
      copy(callback = Some(cb))

    def build(implicit service: smithy4s.Service[Alg], L: LiftIO[F], F: Concurrent[F]): Resource[F, HttpRoutes[F]] =
      Resource.eval(impl).flatMap:
        case (algebra, getAuth, setAuth) =>
          val transformer = Transformer.fromServiceProduct[Alg, Prod, F, A](algebra, getAuth, default, callback)
          val handlersMap = handlers
            .map(handler => handler(transformer.ehbProduct))
            .map(handler => handler.id -> handler)
            .toMap

          val finalAlgebra = transformer.copy(handlers = handlersMap).build

          val routes = SimpleRestJsonBuilder.routes(finalAlgebra).resource
          routes.map(Auth.middleware[F, A](setAuth))

  object Intermediate:
    def apply[Alg[_[_, _, _, _, _]], Prod[_[_, _, _, _, _]], F[_], A](transform: Request[F] => F[A], default: A)
                                                                     (constructor: ServiceConstructor[Alg, F, A])
                                                                     (implicit service: smithy4s.Service[Alg],
                                                                               sp: ServiceProduct.Aux[Prod, Alg],
                                                                               F: Concurrent[F], L: LiftIO[F]): Intermediate[Alg, Prod, F, A] =
      val triple = L.liftIO(IOLocal(default)).map: local =>
        val getAuth = L.liftIO(local.get)
        val setAuth = (request: Request[F]) => transform(request).flatMap(a => LiftIO[F].liftIO(local.set(a)))
        val algebra = constructor match
          case ServiceConstructor.Absent(service) => service
          case ServiceConstructor.Present(service) => service(getAuth)

        (algebra, getAuth, setAuth)

      Intermediate[Alg, Prod, F, A](triple, sp, List.empty, None, None)

    /** A service can be be passed as a constructor requiring an algebra or instantiated service */
    private[conductor] sealed trait ServiceConstructor[Alg[_[_, _, _, _, _]], F[_], A]
    private[conductor] object ServiceConstructor:
      case class Absent[Alg[_[_, _, _, _, _]], F[_], A](service: FunctorAlgebra[Alg, F]) extends ServiceConstructor[Alg, F, A]
      case class Present[Alg[_[_, _, _, _, _]], F[_], A](service: F[A] => FunctorAlgebra[Alg, F]) extends ServiceConstructor[Alg, F, A]

  /** The http4s middleware that sets an internal fiber state to whatever has been extracted from `HttpRequest` */
  def middleware[F[_]: LiftIO: Monad, A](setAuth: Request[F] => F[Unit])(routes: HttpRoutes[F]): HttpRoutes[F] =
    HttpRoutes[F](request => OptionT.liftF(setAuth(request)) *> routes(request))
