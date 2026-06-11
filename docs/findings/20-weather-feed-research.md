# 20 — Weather Feed Research (national + local, streamable vs gated)

> **Status: DONE (2026-06-11).** The operator wanted a weather video feed on the wall — national + local (central Illinois / Normal–Bloomington / Midwest). Research-first, honest streamable-vs-gated. **Outcome:** two national weather channels are confirmed live and shipped — **Fox Weather** and **AccuWeather NOW** (both `status=live` on the NAS prober). A third (WeatherNation) was streamable from the dev egress but the **helper prober fails its TLS handshake** from the NAS, so it was **dropped** (honest play-what-works — a tile that can't validate where it's deployed is worse than none). **No central-Illinois/Midwest LOCAL weather stream is cleanly streamable** — the only US locals with open weather HLS are out-of-region (Baton Rouge LA, Manchester NH), so none was added as "local." NOAA/NWS is radar/data, not video.

## Method

Same rigor as the existing channel curation: a candidate is **addable only if it is public, keyless HLS** — a `.m3u8` returning HTTP 200 with a body beginning `#EXTM3U`, no login/token/geo wall, that follows through to a playable media segment. No stored credentials, no gate-defeating (Trust Bar: honest, keyless only).

Probed via the canonical **iptv-org API** (`channels.json` ⋈ `streams.json`, joined on the `weather` category → 66 weather channels globally, 15 with a stream, 6 US/English), then **curl-verified each** at master → variant → segment level. (The i.mjh.nz FAST playlists have moved to EPG-only XML; the iptv-org API is the current machine-readable source. A first automated sweep produced no structured output — the probing was redone by hand, deterministically.)

## The landscape (honest)

### ✅ Streamable + helper-validated `live` — SHIPPED

| Channel | Master URL | Dev probe | NAS prober |
|---|---|---|---|
| **Fox Weather** | `https://247wlive.foxweather.com/stream/index.m3u8` | master 200 `#EXTM3U` → variant 200 → segment **206 video/MP2T** | **`status=live`** ✓ |
| **AccuWeather NOW** | `https://cdn-ue1-prod.tsv2.amagi.tv/linear/amg00684-accuweather-accuweather-plex/playlist.m3u8` | master 200 → variant 200 → segment **206 video/mp2t** | **`status=live`** ✓ |

Both are free FAST national weather channels (Fox = Fox's 24/7 live; AccuWeather NOW via Amagi/Plex). Added to `helper/src/mymts_helper/channels/seed.json` as the `rest` tier (slugs `fox-weather`, `accuweather-now`) — **available in the menu picker, not occupying a default slot** (the operator selects one into a cell). The helper prober confirmed both `live` from the NAS egress after deploy; if one later rotates its URL it settles `DEAD` honestly (play-what-works — never faked-live).

### ⚠️ Streamable from dev, FAILED the helper's egress — NOT shipped

- **WeatherNation** (`stream.weathernationtv.com/WNTVStirr_…/ND1/playlistSCTE35.m3u8`) — master/variant/segment all 200 (`video/MP2T`, 3.4 MB) **from the dev Mac (curl)**, but the **NAS helper prober** gets `fetch:network_error: [SSL: SSLV3_ALERT_HANDSHAKE_FAILURE]` — its Python/OpenSSL TLS stack can't complete the handshake with the WeatherNation Stirr CDN (the Mac's curl uses a different TLS stack). Because the prober is the gate that the deployment actually uses, the channel would sit permanently `unavailable`/`OFFLINE` — so it was **removed from the seed**. A textbook play-what-works outcome: "streamable" from one egress ≠ "validates where it's deployed." (Backlog: a different WeatherNation CDN endpoint, or a prober TLS-compat tweak, could recover it.)

### ◻︎ Also streamable but NOT added

- **WeatherSpy** (`beaece44.wurl.com/...`) — a Rakuten weather channel, confirmed 200/`#EXTM3U`. Niche brand; left out to avoid clutter, trivially addable later.
- **WBRZ-TV 2.1** (Baton Rouge, LA) and **WMUR-TV 9.1** (Manchester, NH) — local stations whose *weather* substream IS open public HLS (CloudFront / Tubi). **Wrong region** for the operator (central IL), so not added — a Louisiana/NH weather feed isn't "local" here. Evidence that a clean local weather HLS is *possible*, just not for central IL.

### ⛔ Gated / not addable

- **The Weather Channel (proper)** — the real TWC live feed is **TV-provider-login gated** (and app/DRM on its own platform). No public keyless HLS. Not addable without stored credentials (violates posture).
- **Central-Illinois / Midwest local stations** (WMBD, WEEK, WHOI, WCIA, WAND, WICS…) — stream via auth-gated station apps or YouTube *watch pages* (not a direct HLS endpoint). No clean public `.m3u8` found. **This is the honest gap: there is no good central-IL local weather video feed that fits the keyless model.**

### ▭ Not-a-video-feed

- **NOAA / NWS** — radar loops are **images/data, not video streams**. They don't fit the video-tile model. (Possible future *different* widget — see BACKLOG — not a channel.)

## Recommendation

- **Best national:** Fox Weather (mainstream, reliable) with AccuWeather NOW as the alternate — both shipped + live, so play-what-works gives redundancy if one degrades. (WeatherNation would have been a third but fails the helper's TLS — see above.)
- **Best local:** none that fits — central-IL locals are gated. If a local angle is wanted later, it would be a non-video radar/data widget (NWS), logged in BACKLOG, not a video channel.

## Verify

Operator selects a weather channel into a cell (menu picker) and confirms it plays on the panel. The added URLs were segment-verified from the dev egress (US); the box is US/central-IL so geo is not expected to differ.
