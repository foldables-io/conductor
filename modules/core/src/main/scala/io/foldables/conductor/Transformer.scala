package io.foldables.conductor

import cats.Monad
import cats.syntax.all.*

import smithy4s.kinds.{FunctorAlgebra, PolyFunction5}
import smithy4s.{Endpoint, ServiceProduct, ShapeId}

import io.foldables.conductor.Transformer.Eval
import io.foldables.conductor.EndpointHandler.Ops

case class Transformer[F[_]: Monad, A, Alg[_[_, _, _, _, _]], Prod[_[_, _, _, _, _]]]
                      (impl: FunctorAlgebra[Alg, F],
                       getAuth: F[A],
                       sp: ServiceProduct.Aux[Prod, Alg],
                       handlers: Map[ShapeId, EndpointHandler[F, A, ?, ?, ?]],
                       default: Option[(EndpointInfo, A) => F[AuthResult[?]]],
                       callback: Option[(EndpointInfo, A) => F[Unit]]):

  private val poly5 = new PolyFunction5[sp.service.Endpoint, Ops.Of[F, A]]:
    def apply[I, E, O, SI, SO](fa: sp.service.Endpoint[I, E, O, SI, SO]): Ops[F, A, I, E, O, SI, SO] =
      Ops[F, A, I, E, O, SI, SO](fa.id)

  val ehbProduct: Prod[Ops.Of[F, A]] =
    sp.mapK5(sp.endpointsProduct, poly5)

  /** Get the concrete operation from the actual implementation */
  private val run: PolyFunction5[sp.service.Operation, [I, E, O, SI, SO] =>> F[O]] =
    sp.service.toPolyFunction(impl)

  private def compiler: sp.service.EndpointCompiler[Eval[F]] =
    new sp.service.EndpointCompiler[Eval[F]]:
      def apply[I, E, O, SI, SO](fa: sp.service.Endpoint[I, E, O, SI, SO]): I => F[O] =
        (i: I) =>
          val op = fa.wrap(i)
          val endpoint = sp.service.endpoint(op)
          val info = EndpointInfo(sp.service.id, endpoint.id, endpoint.hints)
          val out = run(op)
          val cb = callback.map(f => (a: A) => f(info, a))

          // We technically could invoke the callback here, but I want to avoid calling `getAuth`
          // if there's no `default` and no handler available
          handlers.get(fa.id) match
            case Some(handler) =>
              handler.asInstanceOf[EndpointHandler[F, A, I, E, O]].run(getAuth, out, cb, i)
            case None =>
              EndpointHandler.runDefault[F, A, O](getAuth, out, cb, info, default)

  def build: Alg[Eval[F]] = sp.service.algebra(compiler)


object Transformer:

  /** Evaluate the operation - perform side effects */
  private type Eval = [F[_]] =>> [_, _, O, _, _] =>> F[O]

  def fromServiceProduct[Alg[_[_, _, _, _, _]], Prod[_[_, _, _, _, _]], F[_], A]
                        (impl: FunctorAlgebra[Alg, F],
                         getAuth: F[A],
                         default: Option[(EndpointInfo, A) => F[AuthResult[?]]],
                         callback: Option[(EndpointInfo, A) => F[Unit]])
                        (implicit sp: ServiceProduct.Aux[Prod, Alg],
                                  F: Monad[F]): Transformer[F, A, Alg, Prod] =
    new Transformer[F, A, Alg, Prod](impl, getAuth, sp, Map.empty, default, callback)

