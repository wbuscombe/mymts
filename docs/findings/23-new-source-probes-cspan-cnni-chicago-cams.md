# 23 — New-source probes: C-SPAN, CNN International, Chicago EarthCam cams, Field Museum

**Date:** 2026-07-06
**Scope:** A sourcing pass probing four new channel angles — C-SPAN linear, CNN
International, the EarthCam Wrigleyville cams (Addison & Sheffield; Clark &
Addison), and a Field Museum (Chicago) cam. Each was researched and **live-probed**
from a US residential vantage (curl / ffprobe / yt-dlp), then judged against the
channel registry's admission rules.

**Outcome: 0 added, 5 honest-no.** Two of the five turned up *new* facts worth
recording (CNN International is back on US FAST but token-gated; a real Field Museum
cam exists but is EarthCam-gated). No fragile scraping hack was adopted — the
registry's discipline (plain-request, stable/token-free `.m3u8`; no per-play signing;
no brittle page-scraping) routed every candidate to honest-no.

## Admission bar (why "it plays in a browser" is not enough)

The registry admits a video source only as:
- `kind="hls"` — a **direct https `.m3u8`** that plays on a **plain request** (a stable,
  token-free-or-stable URL): scheme https, public host, no userinfo, path `.m3u8`-shaped,
  no login, no DRM (`#EXT-X-KEY`), **no per-play signing**, and reachable by the helper's
  SSRF-safe fetcher — which sends **no `Referer`** header.
- `kind="youtube"` — an official `youtube.com/@handle/live` (or `/watch?v=`) the in-process
  yt-dlp resolver turns into HLS at runtime, honest-offline when not live.
- `kind="cspan"` — the Senate's own senate.gov ISVP free-gov path only (already built).

A source that only sustains via aggressively-rotating tokens, a required cross-origin
`Referer`, login/DRM, or per-play page-scraping is an **honest-no** — that is the
explicit rule, not a limitation worked around.

---

## C-SPAN linear networks — HONEST_NO (unchanged since 2026-06-24)

Re-probed every plausible path; **nothing has changed.**

- Old branded Akamai `cspan1-lh.akamaihd.net/i/cspan1_1@203903/master.m3u8` → **HTTP 403**.
- Branded linear (community "LegalStream" list): `skystreams-lh.akamaihd.net/i/SkyC1_1@500806/master.m3u8`
  → **HTTP 404** (Akamai edgesuite error; the `@5008xx` stream IDs are stale/rotating).
  C-SPAN2/3 share the same `skystreams` origin, historically MVPD/Adobe-Pass gated. The
  `dotorg*-lh` slots (`dotorg3_1@301851` → 404) are ephemeral per-event, not linear.
- **iptv-org `streams.json`** (fetched fresh): **zero** C-SPAN linear entries.
- `c-span.org/networks` returns 200 but is a TV-Everywhere **login wall** — C-SPAN's own
  words: *"Access to view or listen to the three television networks is reserved for cable
  and satellite TV customers."*
- **FAST carriage** (Pluto, Samsung TV Plus, Tubi, Xumo, Roku, Plex, LG): no genuine C-SPAN
  *linear* channel on any of them in July 2026.

**Verdict:** MVPD-gated / dead-manifest / no FAST carriage. The only free C-SPAN video is
Congressional **event** coverage, which is **already built** (`us-senate-floor`,
`us-senate-committees`, `us-house-floor`, …). Do not re-seed the `skystreams`/`cspan1-lh`/
`dotorg*-lh` hosts — those IDs 404 within days. Revisit only if C-SPAN announces official
free FAST carriage of the *true linear* feed (not a Pluto-style curated clip channel).

---

## CNN International — HONEST_NO (but it **came back** to US FAST)

**New since the 2026-06-24 prune** (when its Wurl/Rakuten host went DNS-dead and it was on
no US FAST platform): CNN International linear **returned** to US FAST — it now rides Pluto
TV's Samsung-TV-Plus ad-stitcher as **"CNNi"** (Pluto id `66c45afaa72e7b0008a2b153`). It
**plays** and is genuinely live video (h264 up to 1280×720 + AAC).

Why it's still **not admissible**:
- The only working manifest is a `stitcher-ipv4.pluto.tv/v2/stitch/embed/hls/...` URL whose
  every variant/segment carries a **JWT `authToken`** (`iss=service-partner-auth.pluto.tv`,
  `partner=samsungtvplus`, **24 h TTL**: `iat 2026-07-06 05:27:28 → exp 2026-07-07 05:27:28`).
  That is **per-session rotating signing** — the exact `no per-play signing` disqualifier.
- The token is only obtained by bouncing through the third-party `jmp2.uk` shortener
  (iptv-org's), which **re-mints it each play** — brittle, and not an official CNN/Pluto
  endpoint.
- It is **ad-stitched** (`features.multiPodAds.enabled=true`) and identity-wise a curated-ish
  WBD/Samsung variant (domestic CNN shows + locally-produced programming), not a clean
  official international linear manifest.
- Every other US "CNN"-named FAST entry (CNN Headlines / Headlines International / Originals /
  Noticias) is the same Pluto rotating-JWT stitcher — curated, not the linear feed.

**Verdict:** honest-no on rotating-token grounds. Recorded as a **watch item**: if CNN/WBD
ever publish a stable token-free international `.m3u8` (or a Pluto endpoint without the
per-play JWT), it becomes addable.

---

## EarthCam Wrigleyville cams — HONEST_NO (both)

Both real cams were located and both **physically play live**, but both fail admission for
the **same EarthCam reasons**:

| Cam | EarthCam page | fecnetwork id | Live probe |
|---|---|---|---|
| Addison & Sheffield | `/usa/illinois/chicago/wrigleyville/` | `13220` | 1280×720 h264/aac, segments advance |
| Clark & Addison (Wrigley Field marquee, SportsWorld) | `/usa/illinois/chicago/wrigleyfield/` | `13661` | 1920×1080 h264/aac, segments advance |

Disqualifiers (verified on both, and re-verified independently):
1. **Referer-gated.** A plain fetch of `videos-3.earthcam.com/fecnetwork/<id>.flv/playlist.m3u8`
   returns **HTTP 403**; it only 200s with `Referer: https://www.earthcam.com/…`. The helper's
   SSRF-safe fetcher sends **no Referer** → it would get 403 → honest-offline. A browser
   `<img>`/hls.js **cannot** set an arbitrary cross-origin `Referer` for the CDN segments, so
   the same-origin widget path can't carry it either.
2. **Time-signed rotating token.** The URL carries `?t=<sig>&td=<YYYYMMDDHHMM mint-time>`; the
   `td` timestamp bounds a server-side expiry window (hours). A hardcoded seed URL goes stale.
3. **yt-dlp's EarthCam extractor is broken** — `yt-dlp -g` returns the `example.mp4` og:video
   placeholder, so a youtube-style runtime resolver cannot be built on it either.

Sustaining these would require a **dedicated EarthCam resolver/proxy** that re-scrapes each cam
page for a fresh `t=/td=` token every cycle **and** injects a `Referer` on every helper-side
fetch **and** re-serves segments same-origin (rewriting the manifest) so the browser/ExoPlayer
never need the Referer. That is a new authenticated-HLS reverse-proxy subsystem whose whole
premise (page-scraping the token) breaks on the next EarthCam redesign — the fragile hack the
sourcing rules exclude. **Deliberately declined.**

---

## Field Museum (Chicago) — HONEST_NO (a real cam **exists**, but EarthCam-gated)

**A real public cam exists:** EarthCam's official Field Museum cam
(`earthcam.com/usa/illinois/chicago/field/`, fecnetwork id `15649`) on the Museum Campus,
framing the downtown skyline + Lake Michigan. It probes to a genuine **live 1920×1080 h264 +
aac** stream (media-sequence advances 90270→90271 over ~8 s = live, no DRM, US-viewable).

But it is the **same EarthCam pattern** as the Wrigleyville cams: the plain manifest is **HTTP
403** (re-verified independently), playback needs a `Referer: earthcam.com` header **and** a
scraped time-signed `t=/td=` token, and `yt-dlp` can't resolve the page. It fails the
`kind=hls` plain-request bar exactly like the two Wrigleyville cams.

> The probe agent initially proposed ADD; on review its own evidence (403-without-Referer +
> rotating token + broken yt-dlp) is the identical disqualifier, so it is **honest-no** for
> consistency. The finding that a Field Museum cam *exists and plays* is recorded so a future
> pass doesn't re-establish it — the blocker is EarthCam's Referer+token gate, not the cam.

---

## Reflection / do-not-re-tread

- **Every** Chicago cam target is behind EarthCam's Referer + rotating-token gate. Adding *any*
  of them means committing to a bespoke, scraping-based EarthCam proxy across all three render
  surfaces — a fragility the project has consistently refused. A future pass should first ask
  whether a **non-EarthCam** Chicago cam (a museum-run or partner HLS without the Referer gate)
  exists before re-probing EarthCam.
- **CNN International** is the one historical honest-no that *changed*: it's back on US FAST, and
  it plays — the only thing standing in the way is Pluto's per-play JWT. Worth a re-check if a
  stable endpoint ever appears.
- **C-SPAN** is fully static: still MVPD-gated, still no FAST, free video still = the already-built
  government event feeds.
