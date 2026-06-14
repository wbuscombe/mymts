// Headless capture of the MyMTS DEMO (phantom-mode) web wall for the docs
// gallery. Runs ONLY against the secret-free demo helper (mock data, no NAS,
// no real upstreams) so it is reproducible and safe to run anywhere — locally
// or in CI. It drives the LAN web client at /app/ and saves a set of PNGs
// covering the feature surface (the wall, the markets/sports/news ticker, the
// settings modal, the honest channel picker).
//
//   Usage:  HELPER_URL=http://127.0.0.1:8091 node capture.mjs
//   (the helper must already be running in PHANTOM_MODE=1 — see README)
//
// The ticker marquee is frozen at its left edge before each shot so frames are
// clean + readable + reproducible (not caught mid-scroll). Video tiles render
// whatever honest state they reach (a demo stream that plays, or the honest
// "on the TV wall" / "Add channel" placeholder) — the layout is deterministic;
// the tile contents are best-effort and not asserted.

import { chromium } from "playwright";
import { mkdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";

const HELPER_URL = (process.env.HELPER_URL || "http://127.0.0.1:8091").replace(/\/+$/, "");
const APP_URL = `${HELPER_URL}/app/`;
const HERE = path.dirname(fileURLToPath(import.meta.url));
const OUT_DIR = process.env.OUT_DIR || path.resolve(HERE, "../../docs/screenshots/web");
const VIEWPORT = { width: 1600, height: 900 };

// Freeze the scrolling ticker at its start so cards are framed cleanly + the
// same way every run (the marquee animation otherwise catches a random offset).
function freezeTicker() {
  const t = document.getElementById("ticker-track");
  if (t) {
    t.style.animation = "none";
    t.style.transform = "translateX(0)";
  }
}

async function waitMode(page, mode, timeout = 30000) {
  await page.waitForFunction(
    (m) => document.getElementById("ticker-mode")?.textContent === m,
    mode,
    { timeout },
  );
  // give the track a beat to render this mode's cards
  await page
    .waitForFunction(
      () => (document.getElementById("ticker-track")?.childElementCount ?? 0) > 0,
      null,
      { timeout: 4000 },
    )
    .catch(() => {});
  await page.waitForTimeout(400);
}

async function fullShot(page, name) {
  await page.evaluate(freezeTicker);
  await page.screenshot({ path: path.join(OUT_DIR, name) });
  console.log("captured", name);
}

async function tickerShot(page, name) {
  await page.evaluate(freezeTicker);
  await page.locator("header.ticker-bar").screenshot({ path: path.join(OUT_DIR, name) });
  console.log("captured", name);
}

async function main() {
  await mkdir(OUT_DIR, { recursive: true });
  const browser = await chromium.launch({
    // muted demo videos may autoplay → a livelier wall; if a stream can't load
    // the tile shows its honest placeholder instead (both are fine to ship).
    args: ["--autoplay-policy=no-user-gesture-required"],
  });
  const ctx = await browser.newContext({ viewport: VIEWPORT, deviceScaleFactor: 1 });
  const page = await ctx.newPage();

  // Enable the NEWS ticker mode (a view pref, default OFF) BEFORE the app boots
  // so we can showcase all three ticker modes in one session.
  await page.addInitScript(() => {
    try {
      localStorage.setItem("mymts.web.prefs.v4", JSON.stringify({ tickerNews: true }));
    } catch {}
  });

  console.log("→", APP_URL);
  await page.goto(APP_URL, { waitUntil: "domcontentloaded" });

  // Wall populated: feed rendered, grid cells built, first ticker render done.
  await page.waitForSelector("#feed .feed-row, #feed .empty", { timeout: 20000 });
  await page.waitForSelector("#grid .tile", { timeout: 20000 });
  await waitMode(page, "MARKETS");
  await page.waitForTimeout(4000); // let video tiles settle into their state

  // 1. Hero — the whole wall (markets ticker + agnostic feed + video grid).
  await fullShot(page, "wall-overview.png");
  // 2. Markets ticker close-up (live indices / FX / crypto / commodities).
  await tickerShot(page, "ticker-markets.png");

  // 3. Sports ticker — the per-sport cards.
  await waitMode(page, "SPORTS");
  await tickerShot(page, "ticker-sports.png");

  // 4. News in the ticker (the 3rd mode).
  await waitMode(page, "NEWS", 45000);
  await tickerShot(page, "ticker-news.png");

  // 5. Settings modal (grid, feed, ticker, sports-league controls).
  await page.click("#gear");
  await page.waitForSelector("#settings-modal:not(.hidden)", { timeout: 5000 });
  await page.waitForTimeout(400);
  await page.screenshot({ path: path.join(OUT_DIR, "settings.png") });
  console.log("captured settings.png");
  await page.click("#settings-close");
  await page.waitForTimeout(200);

  // 6. Channel picker — the honest live / TV-only / offline legend (best-effort:
  // most tile states open the picker on click; if a tile is in a play-gesture
  // state it won't, so we try a few and skip the shot rather than fail).
  try {
    const tiles = page.locator("#grid .tile");
    const count = await tiles.count();
    let opened = false;
    for (let i = 0; i < count && !opened; i++) {
      await tiles.nth(i).click({ timeout: 2000 }).catch(() => {});
      opened = await page
        .waitForSelector("#picker-modal:not(.hidden)", { timeout: 1500 })
        .then(() => true)
        .catch(() => false);
    }
    if (opened) {
      await page.waitForTimeout(400);
      await page.screenshot({ path: path.join(OUT_DIR, "channel-picker.png") });
      console.log("captured channel-picker.png");
    } else {
      console.log("skipped channel-picker.png (no tile opened the picker)");
    }
  } catch (e) {
    console.log("skipped channel-picker.png:", e.message);
  }

  // 7. News-story expand — highlight a headline, then select it to expand the
  // detail view (the Campaign 4.1 feature). Best-effort: if the demo feed is
  // empty, skip rather than fail.
  try {
    await page.keyboard.press("Escape").catch(() => {}); // dismiss any open modal (e.g. picker)
    await page.waitForTimeout(200);
    const story = page.locator("#feed .feed-row").first();
    await story.waitFor({ state: "visible", timeout: 5000 });
    await story.scrollIntoViewIfNeeded().catch(() => {});
    // highlighted state — hover the first headline (accent bar + tint); a feed-
    // pane crop frames the selection clearly.
    await story.hover();
    await page.waitForTimeout(250);
    await page.locator("section.feed-pane").screenshot({
      path: path.join(OUT_DIR, "feed-story-highlighted.png"),
    });
    console.log("captured feed-story-highlighted.png");
    // expanded state — select it; the detail modal shows the item's own fields
    // (source / time / title / summary) + the link-out.
    await story.click();
    await page.waitForSelector("#story-modal:not(.hidden)", { timeout: 5000 });
    await page.waitForTimeout(400);
    await page.screenshot({ path: path.join(OUT_DIR, "feed-story-expanded.png") });
    console.log("captured feed-story-expanded.png");
    await page.keyboard.press("Escape").catch(() => {});
  } catch (e) {
    console.log("skipped news-expand shots:", e.message);
  }

  await browser.close();
  console.log("done →", OUT_DIR);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
