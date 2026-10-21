import { createEffect } from "solid-js";

/** Names the page in the tab and to a screen reader, which reads the title after a client-side navigation. */
export const usePageTitle = (title: () => string | undefined) =>
  createEffect(() => {
    const name = title();
    document.title = name ? `${name} | petshop` : "petshop";
  });
