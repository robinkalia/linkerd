package com.twitter.finagle.buoyant.linkerd

import com.twitter.finagle.{Status => _, _}
import com.twitter.finagle.buoyant.Sampler
import com.twitter.finagle.http._
import com.twitter.finagle.tracing._

/**
 * Typically, finagle clients initialize trace ids to capture a
 * request-response flow.  This doesn't really fit with what we want
 * to capture in the router.  Specifically, we want to capture the
 * 'request' and 'response' portions of a trace individually--ingress
 * to egress in each direction.
 */
object HttpTraceInitializer {
  val role = TraceInitializerFilter.role

  /**
   * The server reads the ctx header ([Headers.Ctx.Key]) to load
   * trace information.
   */
  object server extends Stack.Module1[param.Tracer, ServiceFactory[Request, Response]] {
    val role = HttpTraceInitializer.role
    val description = "Reads trace information from incoming request"

    class Filter(tracer: Tracer) extends SimpleFilter[Request, Response] {

      /**
       * Establish context for this request, as follows:
       * 1. Set the trace id from the context header, if one was provided.
       * 2. Get a new span id for the current request.
       * 3. Use the sample header to determine if the request should be sampled.
       */
      def apply(req: Request, service: Service[Request, Response]) = {
        val headers = req.headerMap
        val ctx = Headers.Ctx.get(headers)
        Headers.Ctx.clear(headers)
        val sampler = Headers.Sample.get(headers).map(Sampler(_))
        Headers.Sample.clear(headers)

        Trace.letIdOption(ctx) {
          Trace.letTracerAndNextId(tracer) {
            sample(sampler) {
              service(req)
            }
          }
        }
      }

      /**
       * Only set _sampled on the trace ID if the sample header provided a
       * sample rate, the sampler determines that the request should be
       * sampled based on the sample rate, and the _sampled field is unset on
       * the current trace ID.
       */
      def sample[T](sampler: Option[Sampler])(f: => T) =
        sampler match {
          case Some(sampler) =>
            val id = Trace.id
            val sampled = id.copy(_sampled = Some(sampler(id.traceId.toLong)))
            Trace.letId(sampled)(f)
          case _ => f
        }
    }

    def make(_tracer: param.Tracer, next: ServiceFactory[Request, Response]) = {
      val param.Tracer(tracer) = _tracer
      new Filter(tracer) andThen next
    }
  }

  /**
   * So, on the client side, we set headers after initializing a new context.
   */
  object client extends Stack.Module1[param.Tracer, ServiceFactory[Request, Response]] {
    val role = HttpTraceInitializer.role
    val description = "Attaches trace information to the outgoing request"

    class Filter(tracer: Tracer) extends SimpleFilter[Request, Response] {
      def apply(req: Request, service: Service[Request, Response]) =
        Trace.letTracerAndNextId(tracer) {
          Headers.Ctx.set(req.headerMap, Trace.id)
          Headers.RequestId.set(req.headerMap, Trace.id.traceId)
          service(req)
        }
    }

    def make(_tracer: param.Tracer, next: ServiceFactory[Request, Response]) = {
      val param.Tracer(tracer) = _tracer
      new Filter(tracer) andThen next
    }
  }
}
