import { For, Show, createResource, onCleanup } from "solid-js";
import { getStats, look } from "../api";

export default function Stats() {
  const [stats, { refetch }] = createResource(getStats);
  const timer = setInterval(refetch, 3000);
  onCleanup(() => clearInterval(timer));

  const pct = (a: number, b: number) => (b === 0 ? 0 : Math.round((a / b) * 100));

  return (
    <div style={{ "max-width": "56rem" }}>
      <div class="crumbs">
        <span class="chip chip-sun">Stats</span>
        <span class="chip">GET /stats</span>
      </div>
      <h1>What the events add up to</h1>
      <p class="lede">
        Every arrival and adoption goes through the outbox and onto the bus. A projection counts them by species. This
        page asks it every three seconds.
      </p>

      <Show when={stats.error}>
        <div class="callout err">Couldn't reach the shop: {String(stats.error?.message ?? stats.error)}</div>
      </Show>

      <Show when={stats.latest}>
        {(t) => (
          <>
            <div class="stickers" style={{ "margin-bottom": "28px" }}>
              <span class="sticker"><span class="live-dot" style={{ margin: "0 9px" }} /> live</span>
              <span class="sticker">
                <span class="dot" style={{ background: "var(--sky)" }}>📬</span>
                <code>{t().events}</code> events
              </span>
              <span class="sticker">
                <span class="dot" style={{ background: "var(--pink)" }}>♻️</span>
                <code>{t().duplicates}</code> duplicates
              </span>
            </div>

            <div class="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>Species</th>
                    <th style={{ "text-align": "right" }}>Arrived</th>
                    <th style={{ "text-align": "right" }}>Adopted</th>
                    <th>Rehomed</th>
                  </tr>
                </thead>
                <tbody>
                  <For each={t().bySpecies}>
                    {(row) => (
                      <tr>
                        <td>{`${look[row.species].emoji} ${row.species}`}</td>
                        <td class="num">{row.arrived}</td>
                        <td class="num">{row.adopted}</td>
                        <td>
                          <div style={{ display: "flex", gap: "10px", "align-items": "center" }}>
                            <div class="bar" style={{ flex: 1 }}>
                              <span style={{ width: `${pct(row.adopted, row.arrived)}%` }} />
                            </div>
                            <code>{pct(row.adopted, row.arrived)}%</code>
                          </div>
                        </td>
                      </tr>
                    )}
                  </For>
                </tbody>
              </table>
            </div>
          </>
        )}
      </Show>
    </div>
  );
}
