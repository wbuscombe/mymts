# Screenshots

The MyMTS gallery. Two kinds of shot:

- [`web/`](web/) — **automated** captures of the LAN web wall (`/app/`), rendered headlessly
  against the **demo / phantom helper** (mock data, no NAS, no secrets) so they're
  reproducible and safe to commit. Regenerate them any time (see below).
- [`device/`](device/) — the operator's **manual** native-TV hero shots (the real wall on a
  dedicated Android TV display). Currently labeled placeholders — see [`device/README.md`](device/README.md).

## `web/` — what each shot shows (demo mode)

These are captured in **phantom mode**, where the helper serves clearly-labeled **SAMPLE**
data and a fixture feed. That honesty is the point — the wall never fakes live data, and the
screenshots show exactly that.

| File | Shows |
|---|---|
| `wall-overview.png` | The whole wall — agnostic feed (left), video grid (right, live HLS in the playable tiles + honest offline tiles), markets ticker on top. |
| `ticker-markets.png` | The markets ticker — indices / FX / gold / oil / yield / crypto, each with the honest `SAMPLE` tag (the demo poller isn't started, so every quote is sample). |
| `ticker-sports.png` | The sports ticker — team game cards (MLB / NBA / NHL) with the ESPN-style league markers + status blocks, `sample`-tagged. |
| `ticker-news.png` | News in the ticker (the 3rd mode) — source-labeled headline cards. |
| `settings.png` | The settings modal — grid rows×cols, feed width/size/recency, source toggles, ticker speed, sports-league toggles. |
| `channel-picker.png` | The channel picker — the real channel lineup with the honest **live / on-the-TV-wall-only / offline** legend. |
| `feed-story-highlighted.png` | A feed headline **selected** (accent-bar highlight) — the highlight→select interaction, keyboard/remote-friendly. |
| `feed-story-expanded.png` | The **news-story detail** (expand) — the item's own source / time / title / summary (inert plain text) + a "Read at source ↗" link-out. MyMTS never fetches the article itself (A1). |

**Honest note on the demo:** phantom mode does **not** exercise the four bespoke
individual-sport cards (PGA / UFC / Tennis / F1) — those need live ESPN data, so the demo
ticker shows team games only. They're a shipped feature; the **device** hero shots (real,
live-data wall) are where they show up. The feed in demo is a 3-item fixture.

## Regenerating the `web/` shots

The capture is a small Playwright tool in [`../../tools/screenshots/`](../../tools/screenshots/):

```bash
# 1. helper in demo mode (mock data, no secrets, no NAS):
cd helper && PHANTOM_MODE=1 PORT=8091 uv run python -m mymts_helper

# 2. in another terminal — install the pinned tooling + capture:
cd tools/screenshots
npm ci && npx playwright install chromium
HELPER_URL=http://127.0.0.1:8091 node capture.mjs   # → docs/screenshots/web/*.png
```

Or run it in CI without a local setup: the **Screenshots** workflow
(`.github/workflows/screenshots.yml`, manual `workflow_dispatch`) boots phantom, captures,
and uploads the gallery as a downloadable **artifact** — it deliberately does **not**
auto-commit binaries on every push.
