import { chromium } from "playwright";

const site = process.env.WEB_URL ?? "http://web:5173";
const perMinute = Number(process.env.RATE ?? "4");
const adoptOneIn = 4;

const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const between = (lo, hi) => lo + Math.random() * (hi - lo);

// A whole page load per visitor, so each one reports its own Web Vitals.
const visit = async (browser, n) => {
  const context = await browser.newContext();
  const page = await context.newPage();
  try {
    await page.goto(`${site}/pets`);
    const cards = page.locator("a.pet-card");
    await cards.first().waitFor({ timeout: 15_000 });
    await pause(between(1_000, 3_000));

    // web-vitals settles LCP at the first click, so this one reports it.
    await cards.nth(Math.floor(Math.random() * Math.min(await cards.count(), 12))).click();
    const adopt = page.locator("button.btn-primary");
    await adopt.waitFor({ timeout: 15_000 });
    await pause(between(2_000, 4_000));
    if (Math.random() * adoptOneIn < 1 && (await adopt.isEnabled())) {
      await adopt.click();
      await page.locator(".callout").waitFor({ timeout: 15_000 });
      await pause(1_000);
    }

    await page.getByRole("link", { name: "Live", exact: true }).click();
    await pause(between(4_000, 8_000));
    await page.getByRole("link", { name: "Stats", exact: true }).click();
    // Longer than the metrics' five second export, so the vitals leave before the page closes.
    await pause(between(7_000, 10_000));
    console.log(`visit ${n}: done`);
  } catch (e) {
    if (stopping) return;
    console.log(`visit ${n}: ${e instanceof Error ? e.message.split("\n")[0] : e}`);
  } finally {
    if (!stopping) await context.close();
  }
};

const browser = await chromium.launch();
let stopping = false;
// Visits in flight are dropped rather than finished: one lasts longer than compose's ten second stop.
for (const signal of ["SIGTERM", "SIGINT"]) {
  process.on(signal, () => {
    stopping = true;
    browser.close().finally(() => process.exit(0));
  });
}

console.log(`${perMinute} visitors a minute to ${site}`);
for (let n = 1; ; n++) {
  visit(browser, n);
  await pause(60_000 / perMinute);
}
