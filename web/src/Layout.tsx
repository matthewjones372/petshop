import { A, type RouteSectionProps } from "@solidjs/router";
import { createSignal } from "solid-js";

function systemDark() {
  return window.matchMedia("(prefers-color-scheme: dark)").matches;
}

function ThemeToggle() {
  const initial = document.documentElement.dataset.theme ?? (systemDark() ? "dark" : "light");
  const [theme, setTheme] = createSignal(initial);
  const flip = () => {
    const next = theme() === "dark" ? "light" : "dark";
    setTheme(next);
    document.documentElement.dataset.theme = next;
    try {
      localStorage.setItem("theme", next);
    } catch {
      // Private windows can refuse storage; the theme still applies for this visit.
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
          <span>Look and feel after <a href="https://eezo.io">eezo.io</a></span>
        </div>
      </footer>
    </>
  );
}
