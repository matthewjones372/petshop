import { defineConfig, type ProxyOptions } from "vite";
import solid from "vite-plugin-solid";

// The shop serves no CORS headers, so the dev server proxies to it. PETSHOP_URL overrides the target.
const shop = process.env.PETSHOP_URL ?? "http://127.0.0.1:8080";
// Nor do the demo's Tempo, Loki and Prometheus, so the browser's spans, lines and metrics reach them the same way.
const tempo = process.env.TEMPO_URL ?? "http://127.0.0.1:4318";
const loki = process.env.LOKI_URL ?? "http://127.0.0.1:3100";
const prometheus = process.env.PROMETHEUS_URL ?? "http://127.0.0.1:9090";

// /pets and /stats are also page routes: a browser asking for HTML gets the app, fetch gets the shop.
const api: ProxyOptions = {
  target: shop,
  bypass: (req) => (req.headers.accept?.includes("text/html") ? "/index.html" : undefined),
};

export default defineConfig({
  plugins: [solid()],
  server: {
    port: 5173,
    proxy: {
      "/pets": api,
      "/stats": api,
      "/events": shop,
      "/reference": shop,
      "/api-docs": shop,
      "/openapi.json": shop,
      "/v1/traces": tempo,
      "/otlp/v1/logs": loki,
      "/api/v1/otlp": prometheus,
    },
  },
  // The demo's headless browser reaches compose's `web` service by that name, which vite refuses unless listed.
  preview: { allowedHosts: ["web"] },
});
