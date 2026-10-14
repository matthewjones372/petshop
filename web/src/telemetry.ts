import { Effect, Layer, ManagedRuntime } from "effect";
import { FetchHttpClient, HttpClient } from "effect/http";
import { OtlpSerialization, OtlpTracer } from "effect/observability";
import { onCLS, onFCP, onINP, onLCP, onTTFB, type Metric } from "web-vitals";

// Same origin: the dev server proxies /v1/traces to Tempo, which sends no CORS headers.
const tracing = OtlpTracer.layer({
  url: "/v1/traces",
  resource: { serviceName: "petshop-web" },
  exportInterval: "1 second",
}).pipe(Layer.provide(OtlpSerialization.layerJson), Layer.provide(FetchHttpClient.layer));

const spanNames = Layer.succeed(HttpClient.SpanNameGenerator, (request) => `${request.method} ${request.url}`);

/** Runs the front end's effects: every HttpClient call is a client span that sends `traceparent` to the shop. */
export const runtime = ManagedRuntime.make(Layer.mergeAll(FetchHttpClient.layer, tracing, spanNames));

const reportError = (kind: string, error: unknown) =>
  runtime.runFork(
    Effect.logError(error instanceof Error ? error.message : String(error)).pipe(
      Effect.withSpan(kind, {
        attributes: {
          "exception.type": error instanceof Error ? error.name : typeof error,
          "exception.message": error instanceof Error ? error.message : String(error),
          "exception.stacktrace": error instanceof Error ? error.stack : undefined,
          "url.path": location.pathname,
        },
      }),
    ),
  );

const reportVital = (metric: Metric) =>
  runtime.runFork(
    Effect.logInfo(`${metric.name} ${metric.rating}`).pipe(
      Effect.withSpan(`web vital ${metric.name}`, {
        attributes: {
          "web_vital.name": metric.name,
          "web_vital.value": metric.value,
          "web_vital.rating": metric.rating,
          "web_vital.navigation_type": metric.navigationType,
          "url.path": location.pathname,
        },
      }),
    ),
  );

/** Uncaught errors and Core Web Vitals each become a short span, their detail a span event. */
export const watchPage = () => {
  addEventListener("error", (e) => reportError("uncaught error", e.error ?? e.message));
  addEventListener("unhandledrejection", (e) => reportError("unhandled rejection", e.reason));
  for (const on of [onCLS, onFCP, onINP, onLCP, onTTFB]) on(reportVital);
};
