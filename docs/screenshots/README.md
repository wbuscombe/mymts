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
| `menu.png` | The **native-style side menu** (the gear opens it) — a CHANNELS list (one row per slot) + WALL actions: the **Preset** selector (server-authoritative wall presets — News / Nature / Space / Chill), Settings, Resync — mirroring the TV app's MenuOverlay (the old flat WALL SETTINGS modal is gone). |
| `wall-preset-nature.png` | A **non-default wall preset applied** — the **Nature** preset switches the whole grid to its server-defined channel-set (Explore Nature Cams · Monterey Bay Aquarium · EarthCam · earthTV) and its 2×2 layout in one step. The HLS nature cams show the honest "on the TV wall" placeholder in the demo browser; the point of the shot is the preset swap (channels + grid), not playback. |
| `settings.png` | The settings modal (reached from the side menu) — grid rows×cols (**2×3 default**), feed width/size/recency, feed-source toggles **grouped by category**, the **captions-off** toggle, ticker speed/motion, sports-league toggles. |
| `slot-controls.png` | The **per-slot controls** (click a tile / a menu channel row) — Channel · Audio · Reconnect · Close, the web analog of the native SlotControlsOverlay. No captions row (these streams' captions are burned-in / unremovable). |
| `channel-picker.png` | The channel picker (opened from a slot's Channel row) — the channel lineup **sectioned by category** (Sports / US News / Global News / … / **Government** / Cameras / Nature / Space) with the honest **live / on-the-TV-wall-only / offline** legend. The free **government** feeds (chamber floors, live committee hearings, federal agencies, the White House feed) live in their own **Government** section so US News reads live-dense. |
| `feed-story-highlighted.png` | A feed headline **selected** (accent-bar highlight) — the highlight→select interaction, keyboard/remote-friendly. |
| `feed-story-expanded.png` | The **news-story detail** (expand) — the item's own source / time / title / summary (inert plain text) + a "Read at source ↗" link-out. MyMTS never fetches the article itself (A1). |
| `control.png` | The **picker control surface** (`/control/`) — the headless-container version's wall editor. The wall's layout in a browser, but **each cell is a feed-PICKER** (a category-grouped channel dropdown) + per-cell **Audio** (single-audible) + **Subtitles** toggles, plus grid rows×cols + preset selectors. **No video decode** — it runs on a phone; every change writes the server-side wall config that `/app/` renders from. |

**Honest note on the demo:** phantom mode does **not** exercise the four bespoke
individual-sport cards (PGA / UFC / Tennis / F1) — those need live ESPN data, so the demo
ticker shows team games only. They're a shipped feature; the **device** hero shots (real,
live-data wall) are where they show up. The feed in demo is a 3-item fixture.

**Headless renderer stream — n/a as a gallery still.** The headless-container version's
output (`ARCHITECTURE.md §27`) is a **live HLS video stream** (the composited wall the
`mymts-renderer` container produces, served at `/api/stream/playlist.m3u8` for VLC / an
Apple TV), not a static page — so it has no automated phantom-mode still here. It's verified
by playing the stream (ffprobe / VLC) on the deployed NAS, not by a committed screenshot;
the `/control/` picker that steers it is captured in [`control.png`](#) above.

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
