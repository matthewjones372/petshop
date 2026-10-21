import { A, type RouteSectionProps } from "@solidjs/router";
import { createSignal, onCleanup } from "solid-js";

type Theme = "light" | "dark";

const systemScheme = window.matchMedia("(prefers-color-scheme: dark)");
const asTheme = (value: string | null | undefined): Theme | undefined =>
  value === "light" || value === "dark" ? value : undefined;

/** No stored theme follows the system; a stored one pins the site against it, even once the system comes to match. */
function ThemeToggle() {
  const [system, setSystem] = createSignal<Theme>(systemScheme.matches ? "dark" : "light");
  const [pinned, setPinned] = createSignal(asTheme(document.documentElement.dataset.theme));
  const theme = () => pinned() ?? system();

  const pin = (next: Theme | undefined) => {
    setPinned(next);
    if (next) document.documentElement.dataset.theme = next;
    else delete document.documentElement.dataset.theme;
    document.querySelector<HTMLMetaElement>('meta[name="color-scheme"]')?.setAttribute("content", next ?? "light dark");
  };

  const onSystem = (e: MediaQueryListEvent) => setSystem(e.matches ? "dark" : "light");
  const onStorage = (e: StorageEvent) => {
    if (e.key === "theme") pin(asTheme(e.newValue));
  };
  systemScheme.addEventListener("change", onSystem);
  addEventListener("storage", onStorage);
  onCleanup(() => {
    systemScheme.removeEventListener("change", onSystem);
    removeEventListener("storage", onStorage);
  });

  // Flipping to what the system shows hands the choice back to it rather than pinning that.
  const flip = () => {
    const target: Theme = theme() === "dark" ? "light" : "dark";
    const next = target === system() ? undefined : target;
    pin(next);
    try {
      if (next) localStorage.setItem("theme", next);
      else localStorage.removeItem("theme");
    } catch (e) {
      // Private windows can refuse storage with a DOMException; the theme still applies for this visit.
      if (!(e instanceof DOMException)) throw e;
    }
  };
  return (
    <button class="icon-btn" onClick={flip} aria-label={`Switch to ${theme() === "dark" ? "light" : "dark"} theme`}>
      {theme() === "dark" ? "☀️" : "🌙"}
    </button>
  );
}

export default function Layout(props: RouteSectionProps) {
  return (
    <>
      <header class="topbar">
        <div class="topbar-inner">
          <A href="/" class="logo">
            <img src="/paw.svg" alt="" />
            <span>petshop</span>
          </A>
          <nav aria-label="Main">
            <ul>
              <li><A href="/pets">Pets</A></li>
              <li><A href="/live">Live</A></li>
              <li><A href="/stats">Stats</A></li>
              <li><a href="/reference" target="_blank" rel="noreferrer">API</a></li>
            </ul>
          </nav>
          <ThemeToggle />
        </div>
      </header>
      <main class="page">{props.children}</main>
      <footer class="footer">
        <div class="footer-inner">
          <span>petshop · Pelican, Lark and friends, one small shop</span>
        </div>
      </footer>
    </>
  );
}
