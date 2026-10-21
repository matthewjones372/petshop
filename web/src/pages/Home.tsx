import { A } from "@solidjs/router";
import { For, Show, createResource } from "solid-js";
import { allSpecies, getStats, listPets, look } from "../api";
import { usePageTitle } from "../title";

const routes = [
  { method: "get", path: "/pets", text: "Every pet in the shop" },
  { method: "get", path: "/pets/{petId}", text: "One pet" },
  { method: "post", path: "/pets/{petId}/adoption", text: "Take a pet home" },
  { method: "get", path: "/stats", text: "What the shop's events add up to" },
];

export default function Home() {
  const [pets] = createResource(listPets);
  const [stats] = createResource(getStats);
  usePageTitle(() => undefined);
  const waiting = (s: string) => pets()?.filter((p) => p.species === s && !p.adopted).length ?? "…";

  return (
    <>
      <section class="hero">
        <div>
          <p class="eyebrow">A small pet shop</p>
          <h1>
            Take a <span class="hl">friend</span> home today
          </h1>
          <p class="lede">
            Cats, dogs, parrots and tortoises arrive all the time. Each one is chipped, and only one person can adopt
            it, however many try at once.
          </p>
          <div class="hero-actions">
            <A href="/pets" class="btn btn-primary">Meet the pets →</A>
            <A href="/live" class="btn">Watch it live</A>
          </div>
          <div class="stickers">
            <span class="sticker"><span class="live-dot" style={{ margin: "0 9px" }} /> shop is open</span>
            <Show when={pets()}>
              <span class="sticker">
                <span class="dot" style={{ background: "var(--sun)" }}>🏷️</span>
                <code>{pets()!.filter((p) => !p.adopted).length}</code> waiting
              </span>
            </Show>
            <Show when={stats()}>
              <span class="sticker">
                <span class="dot" style={{ background: "var(--mint)" }}>📬</span>
                <code>{stats()!.events}</code> events
              </span>
            </Show>
          </div>
        </div>
        <div class="hero-art" aria-hidden="true">
          <span>🐶</span>
        </div>
      </section>

      <h2 class="underline">Who's waiting</h2>
      <ul class="pillar-cards" role="list">
        <For each={allSpecies}>
          {(s) => (
            <li>
              <A class="pillar" href={`/pets?species=${s}`} style={{ "--accent": look[s].colour }}>
                <span class="blob">{look[s].emoji}</span>
                <h3>{s}s</h3>
                <p><span class="big">{waiting(s)}</span> ready for a home</p>
                <small>Browse {s.toLowerCase()}s →</small>
              </A>
            </li>
          )}
        </For>
      </ul>

      <h2 class="underline">The routes underneath</h2>
      <ul class="layer-cards" role="list">
        <For each={routes}>
          {(r) => (
            <li class="layer-card">
              <span class={`method method-${r.method}`}>{r.method.toUpperCase()}</span>
              <div>
                <code>{r.path}</code>
                <p>{r.text}</p>
              </div>
              <a class="btn btn-small" href="/reference" target="_blank" rel="noreferrer">Reference</a>
            </li>
          )}
        </For>
      </ul>
    </>
  );
}
