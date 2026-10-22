import { Data, Effect, Schema } from "effect";
import { HttpClient, HttpClientRequest, HttpClientResponse } from "effect/http";
import { runtime } from "./telemetry";

export const Species = Schema.Literals(["Cat", "Dog", "Parrot", "Tortoise"]);
export type Species = typeof Species.Type;

export const allSpecies: Species[] = ["Cat", "Dog", "Parrot", "Tortoise"];

export const Pet = Schema.Struct({ id: Schema.Number, name: Schema.String, species: Species, adopted: Schema.Boolean });
export type Pet = typeof Pet.Type;

const SpeciesTally = Schema.Struct({ species: Species, arrived: Schema.Number, adopted: Schema.Number });
export type SpeciesTally = typeof SpeciesTally.Type;

const Tally = Schema.Struct({ events: Schema.Number, duplicates: Schema.Number, bySpecies: Schema.Array(SpeciesTally) });
export type Tally = typeof Tally.Type;

/** Every declared failure answers `{ id, message }`; the status says which one, and `kind` which of a status's. */
export class Problem extends Data.TaggedError("Problem")<{ status: number; message: string; kind?: string }> {}

const ProblemBody = Schema.Struct({ message: Schema.String, kind: Schema.optional(Schema.String) });

const answered = <S extends Schema.Top>(schema: S, request: HttpClientRequest.HttpClientRequest) =>
  Effect.gen(function* () {
    const res = yield* (yield* HttpClient.HttpClient).execute(request);
    if (res.status >= 400) {
      const body = yield* HttpClientResponse.schemaBodyJson(ProblemBody)(res).pipe(
        Effect.orElseSucceed(() => ({ message: `HTTP ${res.status}`, kind: undefined })),
      );
      return yield* new Problem({ status: res.status, message: body.message, kind: body.kind });
    }
    return { value: yield* HttpClientResponse.schemaBodyJson(schema)(res), status: res.status };
  });

const call = <S extends Schema.Top>(schema: S, request: HttpClientRequest.HttpClientRequest) =>
  answered(schema, request).pipe(Effect.map(({ value }) => value));

/**
 * The click's span: the client span for the POST, and the shop's spans after it, sit under this one. A 202 is an
 * adoption the chip registry has yet to confirm; the pet reads as taken meanwhile.
 */
export const adopt = (id: number) =>
  answered(Pet, HttpClientRequest.post(`/pets/${id}/adoption`)).pipe(
    Effect.map(({ value, status }) => ({ pet: value, pending: status === 202 })),
    Effect.withSpan("adopt pet", { attributes: { "pet.id": id } }),
  );

export const listPets = () => runtime.runPromise(call(Schema.Array(Pet), HttpClientRequest.get("/pets")));
export const getPet = (id: number) => runtime.runPromise(call(Pet, HttpClientRequest.get(`/pets/${id}`)));
export const adoptPet = (id: number) => runtime.runPromise(adopt(id));
export const getStats = () => runtime.runPromise(call(Tally, HttpClientRequest.get("/stats")));

export const look: Record<Species, { emoji: string; colour: string }> = {
  Cat: { emoji: "🐱", colour: "var(--pink)" },
  Dog: { emoji: "🐶", colour: "var(--sun)" },
  Parrot: { emoji: "🦜", colour: "var(--mint)" },
  Tortoise: { emoji: "🐢", colour: "var(--sky)" },
};
