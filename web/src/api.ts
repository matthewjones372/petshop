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

/** Every declared failure answers `{ id, message }`; the status says which one it is. */
export class Problem extends Data.TaggedError("Problem")<{ status: number; message: string }> {}

const ProblemBody = Schema.Struct({ message: Schema.String });

const call = <S extends Schema.Top>(schema: S, request: HttpClientRequest.HttpClientRequest) =>
  Effect.gen(function* () {
    const res = yield* (yield* HttpClient.HttpClient).execute(request);
    if (res.status >= 400) {
      const body = yield* HttpClientResponse.schemaBodyJson(ProblemBody)(res).pipe(
        Effect.orElseSucceed(() => ({ message: `HTTP ${res.status}` })),
      );
      return yield* new Problem({ status: res.status, message: body.message });
    }
    return yield* HttpClientResponse.schemaBodyJson(schema)(res);
  });

/** The click's span: the client span for the POST, and the shop's spans after it, sit under this one. */
export const adopt = (id: number) =>
  call(Pet, HttpClientRequest.post(`/pets/${id}/adoption`)).pipe(
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
