import { A } from "@solidjs/router";
import { Fiber, Stream } from "effect";
import { For, Show, createSignal, onCleanup } from "solid-js";
import { look } from "../api";
import { runtime } from "../telemetry";
import { usePageTitle } from "../title";
import { shopEvents, shown, type ShopEvent, type Status } from "../feed";

const kept = 60;

const wording: Record<ShopEvent["kind"], { verb: string; chip: string }> = {
  Arrived: { verb: "arrived at the shop", chip: "chip-sun" },
  Adopted: { verb: "went home", chip: "chip-mint" },
  Returned: { verb: "came back: the chip registry said no", chip: "chip-coral" },
};

const statusText: Record<Status, string> = {
  connecting: "connecting",
  live: "live",
  reconnecting: "reconnecting…",
};

export default function Live() {
  const [status, setStatus] = createSignal<Status>("connecting");
  const [events, setEvents] = createSignal<ShopEvent[]>([]);
  usePageTitle(() => "Live");
  const [counts, setCounts] = createSignal({ Arrived: 0, Adopted: 0, Returned: 0 });

  const fiber = runtime.runFork(
    shopEvents(setStatus).pipe(
      Stream.runForEach((event) =>
        shown(event, () => {
          setEvents((seen) => [event, ...seen].slice(0, kept));
          setCounts((c) => ({ ...c, [event.kind]: c[event.kind] + 1 }));
        }),
      ),
    ),
  );
  onCleanup(() => runtime.runFork(Fiber.interrupt(fiber)));

  return (
    <div style={{ "max-width": "52rem" }}>
      <div class="crumbs">
        <span class="chip chip-sun">Live</span>
        <span class="chip">GET /events</span>
      </div>
      <div class="toolbar">
        <h1 style={{ margin: 0 }}>Happening now</h1>
        <span class="sticker" role="status" style={{ animation: "none" }}>
          <span class="live-dot" classList={{ paused: status() !== "live" }} style={{ margin: "0 9px" }} />
          {statusText[status()]}
        </span>
      </div>
      <p class="lede">
        Every arrival, adoption and return, as the shop's events reach it. A dropped connection comes back on its own,
        from now: nothing missed in between is replayed.
      </p>

      <div class="stickers" style={{ "margin-bottom": "28px" }}>
        <For each={Object.keys(wording) as ShopEvent["kind"][]}>
          {(kind) => (
            <span class="sticker">
              <span class={`dot chip ${wording[kind].chip}`} style={{ padding: 0 }}>
                {kind === "Arrived" ? "🛬" : kind === "Adopted" ? "🏡" : "↩️"}
              </span>
              <code>{counts()[kind]}</code> {kind.toLowerCase()}
            </span>
          )}
        </For>
      </div>

      <Show when={events().length > 0} fallback={<p class="muted">Waiting for the first event…</p>}>
        <ul class="layer-cards" role="list">
          <For each={events()}>
            {(e) => (
              <li class="layer-card feed-item">
                <span class={`chip ${wording[e.kind].chip}`}>{e.kind}</span>
                <div>
                  <A href={`/pets/${e.pet.id}`}>{`${look[e.pet.species].emoji} ${e.pet.name}`}</A>
                  <p>{`${e.pet.species.toLowerCase()} #${e.pet.id} ${wording[e.kind].verb}`}</p>
                </div>
                <code>seq {e.seq}</code>
              </li>
            )}
          </For>
        </ul>
      </Show>
    </div>
  );
}
