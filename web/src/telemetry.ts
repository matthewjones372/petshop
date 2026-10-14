import { Effect, Layer, ManagedRuntime, Metric } from "effect";
import { FetchHttpClient, HttpClient } from "effect/http";
import { OtlpLogger, OtlpMetrics, OtlpSerialization, OtlpTracer } from "effect/observability";
import { onCLS, onFCP, onINP, onLCP, onTTFB, type Metric as Vital } from "web-vitals";

const resource = { serviceName: "petshop-web" };

// Same origin for all three: the dev server proxies them to Tempo, Loki and Prometheus, none of which sends CORS headers.
const otlp = Layer.mergeAll(
  OtlpTracer.layer({ url: "/v1/traces", resource, exportInterval: "1 second" }),
  OtlpLogger.layer({ url: "/otlp/v1/logs", resource, exportInterval: "1 second", mergeWithExisting: true }),
  OtlpMetrics.layer({ url: "/api/v1/otlp/v1/metrics", resource, exportInterval: "5 seconds" }),
).pipe(Layer.provide(OtlpSerialization.layerJson), Layer.provide(FetchHttpClient.layer));

// Ids become the shop's own route template, so span metrics keep one series per route rather than one per pet.
const spanNames = Layer.succeed(
  HttpClient.SpanNameGenerator,
  (request) => `${request.method} ${request.url.replace(/\/pets\/\d+/, "/pets/{petId}")}`,
);

/** Runs the front end's effects: every HttpClient call is a client span that sends `traceparent` to the shop. */
export const runtime = ManagedRuntime.make(Layer.mergeAll(FetchHttpClient.layer, otlp, spanNames));

const reportError = (kind: string, error: unknown) =>
  runtime.runFork(
    Effect.logError(`${kind}: ${error instanceof Error ? error.message : String(error)}`).pipe(
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

// The latest reading per vital; each page load reports a handful, too few for a histogram's rate.
const vitals = Metric.gauge("web_vital", { description: "The latest Core Web Vital of each kind" });

// The unit names the Prometheus series: web_vital_milliseconds, and web_vital_ratio for CLS, a unitless score.
const reportVital = (vital: Vital) =>
  runtime.runFork(
    Metric.update(
      Metric.withAttributes(vitals, { name: vital.name, unit: vital.name === "CLS" ? "1" : "milliseconds" }),
      vital.value,
    ).pipe(
      Effect.andThen(Effect.logInfo(`${vital.name} ${Math.round(vital.value * 1000) / 1000} ${vital.rating} on ${location.pathname}`)),
    ),
  );

/** Uncaught errors become a short span and an error line; Core Web Vitals a gauge and a line. */
export const watchPage = () => {
  addEventListener("error", (e) => reportError("uncaught error", e.error ?? e.message));
  addEventListener("unhandledrejection", (e) => reportError("unhandled rejection", e.reason));
  for (const on of [onCLS, onFCP, onINP, onLCP, onTTFB]) on(reportVital);
};
