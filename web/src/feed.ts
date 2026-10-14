import { Effect, Schedule, Schema, Stream } from "effect";
import { Sse } from "effect/encoding";
import { HttpClient, HttpClientResponse } from "effect/http";
import { Pet } from "./api";

export const ShopEvent = Schema.Struct({
  seq: Schema.Number,
  kind: Schema.Literals(["Arrived", "Adopted", "Returned"]),
  pet: Pet,
});

export type ShopEvent = typeof ShopEvent.Type;

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
