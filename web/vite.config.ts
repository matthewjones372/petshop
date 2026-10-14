import { defineConfig, type ProxyOptions } from "vite";
import solid from "vite-plugin-solid";

// The shop serves no CORS headers, so the dev server proxies to it. PETSHOP_URL overrides the target.
const shop = process.env.PETSHOP_URL ?? "http://127.0.0.1:8080";

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
      "/reference": shop,
      "/api-docs": shop,
      "/openapi.json": shop,
    },
  },
});
