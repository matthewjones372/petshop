import { Effect, Option, Schedule, Schema, Stream } from "effect";
import { Sse } from "effect/encoding";
import { Headers, HttpClient, HttpClientResponse, HttpTraceContext } from "effect/http";
import { Pet } from "./api";

export const ShopEvent = Schema.Struct({
  seq: Schema.Number,
  kind: Schema.Literals(["Arrived", "Adopted"]),
  pet: Pet,
  // The trace the shop handled the event in: the adoption's, or the arrival's.
  traceparent: Schema.optional(Schema.NullOr(Schema.String)),
});

export type ShopEvent = typeof ShopEvent.Type;

/**
 * [show] inside a `live event` span that continues the event's trace, so an adoption's trace ends where the page
 * shows it. An event without one starts a trace of its own.
 */
export const shown = <A>(event: ShopEvent, show: () => A) => {
  const parent = event.traceparent ? HttpTraceContext.w3c(Headers.fromInput({ traceparent: event.traceparent })) : Option.none();
  return Effect.sync(show).pipe(
    Effect.withSpan("live event", {
      kind: "consumer",
      parent: Option.getOrUndefined(parent),
      attributes: { "event.seq": event.seq, "event.kind": event.kind, "pet.id": event.pet.id },
    }),
  );
};

export type Status = "connecting" | "live" | "reconnecting";

/** A stream that ends cleanly still means the shop went away, so it is retried like a dropped connection. */
class Disconnected extends Schema.TaggedError<Disconnected>()("Disconnected", {}) {}

// Back off from half a second, never waiting more than ten between attempts.
const reconnect = Schedule.min([Schedule.exponential("500 millis"), Schedule.spaced("10 seconds")]).pipe(
  Schedule.jittered,
);

/**
 * `GET /events`, decoded, reconnecting for as long as it runs. Live only: a reconnect starts from now.
 * Each connection is a `live connection` span, failed by the drop that ends it.
 */
export const shopEvents = (onStatus: (status: Status) => void) =>
  Stream.unwrap(
    Effect.gen(function* () {
      const client = (yield* HttpClient.HttpClient).pipe(HttpClient.filterStatusOk);
      return client.get("/events").pipe(
        Effect.tap(() => Effect.sync(() => onStatus("live"))),
        HttpClientResponse.stream,
        Stream.decodeText,
        Stream.pipeThroughChannel(Sse.decodeDataSchema(ShopEvent)),
        Stream.map((frame) => frame.data),
        Stream.concat(Stream.fail(new Disconnected())),
        Stream.tapError(() =>
          Effect.logWarning("live connection dropped, reconnecting").pipe(
            Effect.andThen(Effect.sync(() => onStatus("reconnecting"))),
          ),
        ),
        Stream.withSpan("live connection"),
        Stream.retry(reconnect),
      );
    }),
  );
