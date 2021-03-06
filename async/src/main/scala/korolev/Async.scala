/*
 * Copyright 2017-2018 Aleksey Fomkin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package korolev

import scala.annotation.implicitNotFound
import scala.collection.generic.CanBuildFrom
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

@implicitNotFound("Instance of Async for ${F} is not found. If you want Future, ensure that execution context is passed to the scope (import korolev.execution.defaultExecutor)")
trait Async[F[_]] {
  def pure[A](value: A): F[A]
  def delay[A](value: => A): F[A]
  def fork[A](value: => A): F[A]
  def unit: F[Unit]
  def fromTry[A](value: => Try[A]): F[A]
  def promise[A]: Async.Promise[F, A]
  def flatMap[A, B](m: F[A])(f: A => F[B]): F[B]
  def map[A, B](m: F[A])(f: A => B): F[B]
  def recover[A, U >: A](m: F[A])(f: PartialFunction[Throwable, U]): F[U]
  def sequence[A, M[X] <: TraversableOnce[X]](in: M[F[A]])(implicit cbf: CanBuildFrom[M[F[A]], A, M[A]]): F[M[A]]
  def run[A, U](m: F[A])(f: Try[A] => U): Unit
}

object Async {

  private val futureInstanceCache =
    mutable.Map.empty[ExecutionContext, Async[Future]]

  trait Promise[F[_], A] {
    def async: F[A]
    def complete(`try`: Try[A]): Unit
    def completeAsync(async: F[A]): Unit
  }

  def apply[F[_]: Async]: Async[F] = implicitly[Async[F]]

  private final class FutureAsync(implicit ec: ExecutionContext) extends Async[Future] {
    val unit: Future[Unit] = Future.successful(())
    def pure[A](value: A): Future[A] = Future.successful(value)
    def delay[A](value: => A): Future[A] = Future.successful(value)
    def fork[A](value: => A): Future[A] = Future(value)
    def fromTry[A](value: => Try[A]): Future[A] = Future.fromTry(value)
    def flatMap[A, B](m: Future[A])(f: A => Future[B]): Future[B] = m.flatMap(f)
    def map[A, B](m: Future[A])(f: A => B): Future[B] = m.map(f)
    def run[A, U](m: Future[A])(f: Try[A] => U): Unit = m.onComplete(f)
    def recover[A, U >: A](m: Future[A])(f: PartialFunction[Throwable, U]): Future[U] = m.recover(f)
    def sequence[A, M[X] <: TraversableOnce[X]](in: M[Future[A]])(implicit cbf: CanBuildFrom[M[Future[A]], A, M[A]]): Future[M[A]] =
      Future.sequence(in)
    def promise[A]: Promise[Future, A] = {
      val promise = scala.concurrent.Promise[A]()
      new Promise[Future, A] {
        val async: Future[A] = promise.future
        def complete(`try`: Try[A]): Unit = {
          promise.complete(`try`)
          ()
        }

        def completeAsync(async: Future[A]): Unit = {
          promise.completeWith(async)
          ()
        }
      }
    }
  }

  /**
    * Creates an Async instance for Future type.
    * Physically one instance per execution context is maintained.
    */
  implicit def futureAsync(implicit ec: ExecutionContext): Async[Future] = {
    futureInstanceCache.synchronized {
      futureInstanceCache.getOrElseUpdate(ec, new FutureAsync())
    }
  }

  implicit final class AsyncOps[F[_]: Async, +A](async: => F[A]) {
    def map[B](f: A => B): F[B] = Async[F].map(async)(f)
    def flatMap[B](f: A => F[B]): F[B] = Async[F].flatMap(async)(f)
    def recover[U >: A](f: PartialFunction[Throwable, U]): F[U] = Async[F].recover[A, U](async)(f)
    def run[U](f: Try[A] => U): Unit = Async[F].run(async)(f)
    def runOrReport[U](f: A => U)(implicit er: Reporter): Unit =
      Async[F].run(async) {
        case Success(x) => f(x)
        case Failure(e) => er.error("Unhandled error", e)
      }
    def runIgnoreResult(implicit er: Reporter): Unit =
      Async[F].run(async) {
        case Success(_) => // do nothing
        case Failure(e) => er.error("Unhandled error", e)
      }
  }
}
