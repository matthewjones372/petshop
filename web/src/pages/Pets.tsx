import { A, useSearchParams } from "@solidjs/router";
import { For, Show, createMemo, createResource, createSignal } from "solid-js";
import { allSpecies, listPets, look, type Species } from "../api";
import { usePageTitle } from "../title";

const pageSize = 48;

type Status = "waiting" | "adopted" | "all";

export default function Pets() {
  const [pets, { refetch }] = createResource(listPets);
  const [params, setParams] = useSearchParams<{ species?: string; status?: string; q?: string }>();
  const [shown, setShown] = createSignal(pageSize);

  const species = () => params.species as Species | undefined;
  const status = (): Status => (params.status as Status) ?? "waiting";
  const query = () => (params.q ?? "").toLowerCase();
  usePageTitle(() => (species() ? `${species()}s` : "Pets"));

  const matches = createMemo(() =>
    (pets() ?? []).filter(
      (p) =>
        (!species() || p.species === species()) &&
        (status() === "all" || p.adopted === (status() === "adopted")) &&
        p.name.toLowerCase().includes(query()),
    ),
  );
  const count = (s?: Species) => (pets() ?? []).filter((p) => !s || p.species === s).length;

  // Typing replaces the history entry, so Back leaves the search rather than undoing it a letter at a time.
  const pick = (next: Record<string, string | undefined>, typed = false) => {
    setShown(pageSize);
    setParams(next, { replace: typed });
  };

  return (
    <div class="shell">
      <aside class="sidebar">
        <section>
          <label for="pet-search" class="visually-hidden">Find a pet by name</label>
          <input
            id="pet-search"
            class="search"
            type="search"
            placeholder="Find by name…"
            autocomplete="off"
            autocorrect="off"
            autocapitalize="off"
            spellcheck={false}
            value={params.q ?? ""}
            onInput={(e) => pick({ q: e.currentTarget.value || undefined }, true)}
          />
        </section>
        <section>
          <h2>Species</h2>
          <ul>
            <li>
              <button aria-pressed={!species()} onClick={() => pick({ species: undefined })}>
                Everyone <span class="count">{count()}</span>
              </button>
            </li>
            <For each={allSpecies}>
              {(s) => (
                <li>
                  <button aria-pressed={species() === s} onClick={() => pick({ species: s })}>
                    <span>{look[s].emoji} {s}s</span> <span class="count">{count(s)}</span>
                  </button>
                </li>
              )}
            </For>
          </ul>
        </section>
        <section>
          <h2>Status</h2>
          <ul>
            <For each={["waiting", "adopted", "all"] as Status[]}>
              {(s) => (
                <li>
                  <button aria-pressed={status() === s} onClick={() => pick({ status: s === "waiting" ? undefined : s })}>
                    {s === "waiting" ? "Waiting for a home" : s === "adopted" ? "Already adopted" : "Both"}
                  </button>
                </li>
              )}
            </For>
          </ul>
        </section>
      </aside>

      <div>
        <div class="crumbs">
          <span class="chip chip-sun">Pets</span>
          <Show when={species()}>
            <span class="chip">{species()}s</span>
          </Show>
        </div>
        <div class="toolbar">
          <h1 style={{ margin: 0 }}>{species() ? `${species()}s` : "Every pet"}</h1>
          <button class="btn btn-small" onClick={refetch}>↻ Refresh</button>
        </div>
        <p class="muted">
          <Show when={pets()} fallback="Fetching the shop…">
            {matches().length} {matches().length === 1 ? "pet" : "pets"} match. New arrivals turn up every few seconds.
          </Show>
        </p>
        <Show when={pets.error}>
          <div class="callout err">Couldn't reach the shop: {String(pets.error?.message ?? pets.error)}</div>
        </Show>

        <ul class="pet-grid">
          <For each={matches().slice(0, shown())}>
            {(p) => (
              <li>
                <A class="pillar pet-card" href={`/pets/${p.id}`} style={{ "--accent": look[p.species].colour }}>
                  <span class="blob">{look[p.species].emoji}</span>
                  <p class="name">{p.name}</p>
                  <code>#{p.id}</code>
                  <div class="meta">
                    <span class="chip">{p.species}</span>
                    <Show when={p.adopted} fallback={<span class="chip chip-mint">Waiting</span>}>
                      <span class="chip chip-coral">Adopted</span>
                    </Show>
                  </div>
                </A>
              </li>
            )}
          </For>
        </ul>

        <Show when={matches().length > shown()}>
          <div class="center">
            <button class="btn" onClick={() => setShown(shown() + pageSize)}>
              Show more ({matches().length - shown()} left)
            </button>
          </div>
        </Show>
      </div>
    </div>
  );
}
