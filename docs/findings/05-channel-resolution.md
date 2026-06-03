# Finding 05 — Channel-resolution investigation (helper-side)

> **Status:** ✅ 8 channels resolve live on the helper after this track, up from 2 (4× increase). The prober now validates **master + at least one variant** before marking a channel live, closing the "master-OK / variant-FAIL" gap (NASA TV pattern) at the helper layer. Three channels are honestly carried-forward to BACKLOG as "no working public endpoint exists" rather than seeded with stale URLs.

## What changed in this track

### 1. Prober deepening — master + variant validation

The prober previously validated only the **master** HLS manifest (`#EXTM3U` prefix). NASA TV exposed the gap: its master fetches fine, but its variant playlists return HTTP 404 for downstream consumers — the helper would say `live` while the player couldn't actually play. The Stage 3 polish-pass workaround was a `LineupSelector.DENY` set excluding NASA TV; this track fixes the cause.

After this commit (`helper/src/mymts_helper/channels/prober.py`):

1. Fetch master through the SSRF-safe `fetcher` (unchanged).
2. Validate the master is `#EXTM3U`-prefixed (unchanged).
3. **If the master is a media playlist** (`#EXTINF` present, no `#EXT-X-STREAM-INF`) → already single-rendition → mark live with master URL.
4. **Otherwise** parse the master, find the first `#EXT-X-STREAM-INF` entry's URL line, absolutize against the master's URL, and **fetch that variant** through the SSRF-safe `fetcher`.
5. Variant must respond HTTP 2xx + `#EXTM3U`. Anything else → record `variant_http_<code>`, `variant_fetch:<reason>`, `variant_not_hls_manifest`, or `variant_missing_in_master` — a precise error class, not silent unavailable.

All SSRF guards (https-only, DNS-pinned, RFC1918/loopback/CGNAT/ULA rejection, bounded body, bounded time, redirect re-validation) apply to the variant fetch exactly as they did to the master. **No new egress surface.** See `docs/THREAT-MODEL.md` T-H2 / T-H5 — the contract is unchanged at the boundary; the prober's definition of "live" just got tighter.

10 unit tests in `helper/tests/test_channels_prober.py`:
- `_looks_like_hls_manifest` accepts BOM-prefixed `#EXTM3U`, rejects HTML/blank.
- `_is_media_playlist` recognises `#EXTINF`-only bodies, refuses a master that mentions `#EXTINF` in a comment.
- `_pick_variant_url` returns the first variant after `#EXT-X-STREAM-INF`; absolutizes relative against the master URL; keeps an absolute https variant; **rejects** an absolute http variant (downgrade); returns None for a media playlist; conservatively returns None when intermediate `#`-prefixed lines interrupt the expectation (better to mark unavailable than pick the wrong rendition).

### 2. Seed URL rotation — verified working candidates

Each new candidate was fetched and confirmed `HTTP 200 + #EXTM3U` from a US residential IP before being written into the seed.

| slug | old (failure) | new (verified) |
|---|---|---|
| cbs-sports-hq | `cbssports-cbssports-1-us.samsung.wurl.tv` (DNS) | `https://propee33f9c2.airspace-cdn.cbsivideo.com/index.m3u8` (CBSi airspace) |
| bbc-news | `vs-cmaf-pushb-uk-live...` (HTTP 403 — UK-only shard) | `https://vs-hls-push-ww-live.akamaized.net/x=4/i=urn:bbc:pips:service:bbc_news_channel_hd/mobile_wifi_main_hd_abr_v2.m3u8` (worldwide shard) |
| cnn | rakuten.wurl.tv (DNS) | `https://turnerlive.warnermediacdn.com/hls/live/586495/cnngo/cnn_slate/VIDEO_2_1964000.m3u8` (publicly-streamable slate feed; AES-128) |
| livenow-fox | `livenowfromfox.akamaized.net` (DNS) | `https://pb-k5p02dtnr2162.akamaized.net/LiveNOW_from_FOX.m3u8` (Akamai) |
| newsmax | samsung.wurl.tv (DNS) | `https://nmxlive.akamaized.net/hls/live/529965/Live_1/index.m3u8` (Akamai) |
| france24-en | `f24hls-i.akamaihd.net` (HTTP 400 — origin decommissioned) | `https://live.france24.com/hls/live/2037218-b/F24_EN_HI_HLS/master_5000.m3u8` (official direct origin) |
| sky-news | `skynews2-plutolive-vo.akamaized.net` (HTTP 400) | `https://linear417-gb-hls1-prd-ak.cdn.skycdp.com/100e/Content/HLS_001_1080_30/Live/channel(skynews)/index_1080-30.m3u8` (Sky CDN direct) |

### 3. Geo-block characterization (no circumvention added)

| slug | finding |
|---|---|
| **bbc-news** | Old URL was UK-only by shard (`pushb`/`-uk-live`). Worldwide shard (`push`/`-ww-live`) works without circumvention — that's a better URL, not a bypass. **Now live.** |
| **c-span** | Geo isn't the cause; their public web player wraps the stream in a session-token handshake (no static `.m3u8`). `iptv-org` issue #5971 has been unable to find a stable maintained alternative. **No working public endpoint exists** — BACKLOG. |
| **sky-news** | Candidate works from US Mac AND from inside the NAS network when tested directly with curl; the helper's httpx fetch returned 403 in the deploy-time probe (likely a transient 403 or a header / encoding mismatch). Re-probing on the next 30-min cycle. Documented for follow-up. |

Per the operator's standing rule, **no egress-circumvention / proxy / VPN-routing** was added — geo-block circumvention is explicitly an operator-decision item logged in BACKLOG with the threat-model implications stated.

### 4. Carried-forward — no public HLS endpoint currently exists

These are honestly not addressable at the helper layer:

| slug | reason |
|---|---|
| **iss-feed** | Historical UStream `iphone-streaming.ustream.tv` (SSL cert mismatch then dead) and CloudFront `d2ai41bknpka2u.cloudfront.net/.../iss.stream_source/chunklist.m3u8` (DNS-fails) are decommissioned. NASA's current standalone ISS HDEV is YouTube-only; HLS sidecar would be a separate scoped effort. NASA TV NTV1 carries ISS Earth-view during off-programming hours and is the honest substitute. **BACKLOG.** |
| **cnn-international** | No public HLS endpoint exists; the channel is no longer carried on any free public FAST platform. **BACKLOG.** |
| **c-span** | See §3 — session-token-gated, no static `.m3u8`. **BACKLOG.** |
| **white-house-tv** | `wh.gov/live/playlist.m3u8` is a placeholder URL (HTTP 404). No public 24/7 White House TV HLS endpoint exists in the way iptv-org or LegalStream catalogs maintained streams; current distribution is via embedded players on whitehouse.gov which require JS. **No-action / drop from seed** is also a reasonable call for a future cleanup. |

Three remaining unavailable channels are stale historical seeds (al-jazeera-en, cgtn-en, trt-world) — left as-is; they may resolve at a future probe cycle, and the wall surfaces them honestly when they don't.

## Resolution map after this track

| status | count | channels |
|---|---|---|
| **live** | **8** | bbc-news, cbs-sports-hq, cnn (slate), dw-news-en, france24-en, livenow-fox, newsmax, redbull-tv |
| unavailable | 9 | al-jazeera-en, c-span, cgtn-en, cnn-international, iss-feed, nasa-tv (variant-FAIL, honestly reclassified), sky-news (in flight), trt-world, white-house-tv |

The default `LineupSelector.forWall(N)` fills from the 8 live channels. The operator's preferred lineup (CBS Sports HQ → BBC News → CNN → LiveNOW from FOX) now resolves **all four** to playable channels — the wall no longer cycles two channels into four slots.

## NASA TV reclassification — honest, not regressive

NASA TV was previously marked `status=live` (master passed) but never actually played (variant-fetch failed in the player). It's now honestly `unavailable` with `last_error=variant_http_404` — the helper's definition of "live" matches what the TV-side player can reach. The `LineupSelector.DENY` workaround added in the Stage 3 polish pass (`docs/findings/03-stage-3-wall.md §6.3`) is no longer load-bearing; the truth is enforced at the helper layer. The slug stays seeded; if NASA TV's variants become reachable again, the next probe cycle re-marks it live without code changes.

## Standing rules
- **unrelated host services: never touched.**
- **WyzeGrid** untouched (helper-side track, no `.182` involvement).
- Helper tests green: 132 passed (122 prior + 10 new `test_channels_prober`).
- Helper redeployed to `<USER>@<HOST>:/srv/docker/mymts-helper/`; non-root, `read_only: true`, `cap_drop: ALL`, dedicated bridge network — never the unrelated host container.
