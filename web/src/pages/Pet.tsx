import { A, useParams } from "@solidjs/router";
import { Match, Show, Switch, createResource, createSignal } from "solid-js";
import { Problem, adoptPet, getPet, look } from "../api";

type Outcome = { ok: boolean; text: string };

const explain: Record<number, string> = {
  404: "That pet isn't in the shop.",
  409: "Someone got there first, or is adopting this pet right now.",
  422: "The chip registry has no chip for this pet, so it can't go home yet.",
  503: "The adoption couldn't be finished just now. Try again in a moment.",
};

export default function PetPage() {
  const params = useParams<{ id: string }>();
  const [pet, { mutate }] = createResource(() => Number(params.id), getPet);
  const [busy, setBusy] = createSignal(false);
  const [outcome, setOutcome] = createSignal<Outcome>();

  const adopt = async () => {
    setBusy(true);
    setOutcome();
    try {
      const { pet: adopted, pending } = await adoptPet(Number(params.id));
      mutate(adopted);
      setOutcome({
        ok: true,
        text: pending
          ? `${adopted.name} is yours once the chip registry confirms it.`
          : `${adopted.name} is going home with you. 🎉`,
      });
    } catch (e) {
      if (!(e instanceof Problem)) throw e;
      setOutcome({ ok: false, text: `${explain[e.status] ?? "Something went wrong."} (${e.status}: ${e.message})` });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div style={{ "max-width": "48rem" }}>
      <div class="crumbs">
        <A href="/pets" class="chip chip-sun" style={{ "text-decoration": "none" }}>← Pets</A>
        <Show when={pet()}>
          <span class="chip">{pet()!.species}s</span>
        </Show>
      </div>

      <Switch>
        <Match when={pet.error}>
          <h1>No such pet</h1>
          <div class="callout err">{pet.error.message}</div>
        </Match>
        <Match when={pet()}>
          {(p) => (
            <>
              <div class="toolbar">
                <h1 style={{ margin: 0 }}>
                  {look[p().species].emoji} {p().name}
                </h1>
                <Show when={p().adopted} fallback={<span class="chip chip-mint">Waiting for a home</span>}>
                  <span class="chip chip-coral">Adopted</span>
                </Show>
              </div>

              <div class="table-scroll kv">
                <table>
                  <tbody>
                    <tr><td>id</td><td><code>{p().id}</code></td></tr>
                    <tr><td>species</td><td>{p().species}</td></tr>
                    <tr><td>adopted</td><td><code>{String(p().adopted)}</code></td></tr>
                    <tr><td>route</td><td><span class="method method-post">POST</span> <code>/pets/{p().id}/adoption</code></td></tr>
                  </tbody>
                </table>
              </div>

              <Show when={outcome()}>
                {(o) => <div class={`callout ${o().ok ? "ok" : "err"}`}>{o().text}</div>}
              </Show>

              <div style={{ "margin-top": "24px" }}>
                <button class="btn btn-primary" disabled={busy() || p().adopted} onClick={adopt}>
                  {busy() ? "Checking the chip…" : p().adopted ? "Already home" : `Adopt ${p().name}`}
                </button>
              </div>
            </>
          )}
        </Match>
      </Switch>
    </div>
  );
}
