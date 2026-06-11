# 20 — Weather Feed Research (national + local, streamable vs gated)

> **Status: DONE (2026-06-11).** The operator wanted a weather video feed on the wall — national + local (central Illinois / Normal–Bloomington / Midwest). Research-first, honest streamable-vs-gated. **Outcome:** three national weather channels are confirmed public keyless HLS and were added (Fox Weather, AccuWeather NOW, WeatherNation). **No central-Illinois/Midwest LOCAL weather stream is cleanly streamable** — the only US locals with open weather HLS are out-of-region (Baton Rouge LA, Manchester NH), so none was added as "local." NOAA/NWS is radar/data, not video.

## Method

Same rigor as the existing channel curation: a candidate is **addable only if it is public, keyless HLS** — a `.m3u8` returning HTTP 200 with a body beginning `#EXTM3U`, no login/token/geo wall, that follows through to a playable media segment. No stored credentials, no gate-defeating (Trust Bar: honest, keyless only).

Probed via the canonical **iptv-org API** (`channels.json` ⋈ `streams.json`, joined on the `weather` category → 66 weather channels globally, 15 with a stream, 6 US/English), then **curl-verified each** at master → variant → segment level. (The i.mjh.nz FAST playlists have moved to EPG-only XML; the iptv-org API is the current machine-readable source. A first automated sweep produced no structured output — the probing was redone by hand, deterministically.)

## The landscape (honest)

### ✅ Streamable — public keyless HLS (national), ADDED

| Channel | Master URL | Evidence |
|---|---|---|
| **Fox Weather** | `https://247wlive.foxweather.com/stream/index.m3u8` | master 200 `#EXTM3U` → variant 200 → segment **206 video/MP2T** |
| **AccuWeather NOW** | `https://cdn-ue1-prod.tsv2.amagi.tv/linear/amg00684-accuweather-accuweather-plex/playlist.m3u8` | master 200 → variant 200 → segment **206 video/mp2t** |
| **WeatherNation** | `https://stream.weathernationtv.com/WNTVStirr_eokxldieulowixkdimn/ND1/playlistSCTE35.m3u8` | master 200 → variant 200 → segment **200 video/MP2T, 3.4 MB** |

All three are free FAST national weather channels (Fox = Fox's 24/7 live; AccuWeather NOW via Amagi/Plex; WeatherNation via Stirr). Added to `helper/src/mymts_helper/channels/seed.json` as the `rest` tier (slugs `fox-weather`, `accuweather-now`, `weathernation`) — **available in the menu picker, not occupying a default slot** (the operator selects one into a cell). The helper prober validates them live on the NAS egress; if one rotates its URL it settles `DEAD` honestly (play-what-works — never faked-live).

### ◻︎ Also streamable but NOT added

- **WeatherSpy** (`beaece44.wurl.com/...`) — a Rakuten weather channel, confirmed 200/`#EXTM3U`. Niche brand; left out to avoid clutter, trivially addable later.
- **WBRZ-TV 2.1** (Baton Rouge, LA) and **WMUR-TV 9.1** (Manchester, NH) — local stations whose *weather* substream IS open public HLS (CloudFront / Tubi). **Wrong region** for the operator (central IL), so not added — a Louisiana/NH weather feed isn't "local" here. Evidence that a clean local weather HLS is *possible*, just not for central IL.

### ⛔ Gated / not addable

- **The Weather Channel (proper)** — the real TWC live feed is **TV-provider-login gated** (and app/DRM on its own platform). No public keyless HLS. Not addable without stored credentials (violates posture).
- **Central-Illinois / Midwest local stations** (WMBD, WEEK, WHOI, WCIA, WAND, WICS…) — stream via auth-gated station apps or YouTube *watch pages* (not a direct HLS endpoint). No clean public `.m3u8` found. **This is the honest gap: there is no good central-IL local weather video feed that fits the keyless model.**

### ▭ Not-a-video-feed

- **NOAA / NWS** — radar loops are **images/data, not video streams**. They don't fit the video-tile model. (Possible future *different* widget — see BACKLOG — not a channel.)

## Recommendation

- **Best national:** Fox Weather (mainstream, reliable) with AccuWeather NOW + WeatherNation as alternates — all three added, so play-what-works gives redundancy if one degrades.
- **Best local:** none that fits — central-IL locals are gated. If a local angle is wanted later, it would be a non-video radar/data widget (NWS), logged in BACKLOG, not a video channel.

## Verify

Operator selects a weather channel into a cell (menu picker) and confirms it plays on the panel. The added URLs were segment-verified from the dev egress (US); the box is US/central-IL so geo is not expected to differ.
