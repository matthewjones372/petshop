export type Species = "Cat" | "Dog" | "Parrot" | "Tortoise";

export const allSpecies: Species[] = ["Cat", "Dog", "Parrot", "Tortoise"];

export type Pet = { id: number; name: string; species: Species; adopted: boolean };

export type SpeciesTally = { species: Species; arrived: number; adopted: number };

export type Tally = { events: number; duplicates: number; bySpecies: SpeciesTally[] };

/** Every declared failure answers `{ id, message }`; the status says which one it is. */
export class Problem extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, init);
  if (!res.ok) {
    const body = await res.json().catch(() => ({ message: res.statusText }));
    throw new Problem(res.status, body.message ?? res.statusText);
  }
  return res.json();
}

export const listPets = () => call<Pet[]>("/pets");
export const getPet = (id: number) => call<Pet>(`/pets/${id}`);
export const adoptPet = (id: number) => call<Pet>(`/pets/${id}/adoption`, { method: "POST" });
export const getStats = () => call<Tally>("/stats");

export const look: Record<Species, { emoji: string; colour: string }> = {
  Cat: { emoji: "🐱", colour: "var(--pink)" },
  Dog: { emoji: "🐶", colour: "var(--sun)" },
  Parrot: { emoji: "🦜", colour: "var(--mint)" },
  Tortoise: { emoji: "🐢", colour: "var(--sky)" },
};
