# 24 — Fresh-source pass: international news restored, CBS News 24/7 added

**Date:** 2026-08-18
**Scope:** Re-source the three international news channels pruned in the v0.2.1 channel
cleanup (Al Jazeera English, CGTN English, TRT World), evaluate national **CBS News 24/7**
and an official **CBS News Chicago** first-party HLS candidate, and re-validate the two
existing *broadcaster-handle* entries (NASA, EarthCam).

**Outcome: 4 added, 2 honest-no, 2 existing entries re-confirmed as-is.**
Lineup **52 → 56**. No category added or reordered, so the native app is byte-untouched
and no APK release is warranted.

---

## Phase 0 — why they were pruned (the gate)

Recovered from primary sources, not memory: commit `c3b5866` *(fix(channels): quality
cleanup — prune dead, Government category, fix presets, 2026-06-22)*, released in
`ec832a0` *(chore(release): cut v0.2.1)*, and `CHANGELOG.md` §"Channel-selection findings
(lineup 56 → 52)".

> **B4:** pruned `al-jazeera-en`, `cgtn-en`, `trt-world` — persistently dead (~1000
> consecutive dns/SSL failures; hosts gone).

The removed seed rows confirm it — all three were **direct-HLS** origins:
`live-hls-web-aje.getaj.net`, `livecgtnen.v.kcdnvip.com`, `tv-trtworld.live.trt.com.tr`.

**Conclusion: superseded-endpoint evidence, not a sourcing judgment.** The prune was about
three dead *hosts*, not about stream quality, editorial standing, or a decision that these
broadcasters don't belong in the lineup. Nothing in the commit, the release notes, or the
findings set records a substantive objection to the sources themselves. That makes a
re-source with a *different, live* official endpoint legitimate — and it gets the **full**
current admission bar, with no credit for having been carried before.

---

## Admission bar applied (unchanged from finding 23)

Every candidate had to clear all of it, from the helper's own credential-free path:

- resolved and fetched through the project's real code — the in-process `yt_dlp` resolver
  (`channels/youtube_resolver.py`, `is_live`-gated, no `player_client` pin) for
  `kind='youtube'`, then the SSRF-safe `fetcher` (https-only, cert verification **on**,
  private-range rejection, bounded body/time) for every hop;
- **master → variant → one real segment** actually fetched and its container sniffed;
- **no** credentials, cookies, injected tokens, `Referer` spoofing, or DRM workarounds;
- **no** DRM (`#EXT-X-KEY` must be absent), **no** per-play signing;
- **two probes several minutes apart**, both clean;
- for YouTube sources, the seed pins the **`@handle`/live** URL — **never** a video id,
  which rotates whenever the broadcaster restarts the stream.

---

## Results

| Candidate | Kind | Verdict | Top rung | Segment |
|---|---|---|---|---|
| **CBS News 24/7** (first-party) | `hls` | **ADD** | 1280×720 @29.97, 3.02 Mb/s (6 rungs) | 1.81 / 1.87 MB MPEG-TS |
| **Al Jazeera English** (official YouTube) | `youtube` | **ADD** | 1920×1080 @30, 4.56 Mb/s (6 rungs) | 1.63 / 1.47 MB MPEG-TS |
| **TRT World** (official YouTube) | `youtube` | **ADD** | 1920×1080 @30, 4.56 Mb/s (6 rungs) | 0.47 / 0.55 MB MPEG-TS |
| **CGTN English** (official YouTube) | `youtube` | **ADD** | 1920×1080 @30, 5.42 Mb/s (6 rungs) | 1.01 / 0.95 MB MPEG-TS |
| **CBS News Chicago** (first-party) | — | **HONEST-NO** | master OK, 6 rungs advertised | **all 6 variants HTTP 404** |
| **NASA TV** (`nasa-tv`, existing) | `hls` | **unchanged, DENY confirmed** | master OK, 3 rungs advertised | **all 3 variants HTTP 404** |
| **ISS / @NASA handle** (`iss-feed`, existing) | `youtube` | **unchanged** | 1280×720 @60, 2.92 Mb/s | 0.93 MB MPEG-TS |
| **EarthCam** (`earthcam-live`, existing) | `youtube` | **unchanged** | 1920×1080 @30, 5.42 Mb/s | 0.67 / 2.70 MB MPEG-TS |

No candidate's chain contained `#EXT-X-KEY`, a plain-`http://` sub-resource, or a per-play
signature. Both probes agreed on every row — the four passes passed twice, and the two
failures failed twice.

### CBS News 24/7 — why the first-party HLS and not the YouTube handle

Both work. The official `@CBSNews/live` handle resolves and plays cleanly, but CBS also
publishes the feed as a **stable, token-free first-party `.m3u8`**, which is the registry's
*preferred* shape: no runtime resolver dependency, no ~6 h manifest expiry to refresh, no
exposure to yt-dlp extraction rot. The direct-HLS origin was taken for that reason.

The variant playlists carry `#EXT-X-CUE-OUT-CONT` / SCTE-35 ad markers (normal for a free
linear feed) and their segment URIs carry a `?m=<stamp>` cache-buster that is **constant
across segments and across both probes** — an origin-emitted config stamp we simply follow,
not a per-play signature we mint. The seeded URL itself is plain and unsigned.

### CBS News Chicago — honest-no

The first-party master serves **HTTP 200** with a complete six-rung ladder, so a shallow
"is it 200?" check would call it live. Every one of the six advertised variants returns
`<h1>error 404</h1>`. This is precisely the **master-OK / variant-FAIL** pattern the
prober's deepened validation (2026-06-03) was built to catch, and it is why the helper
follows the master one level before ever marking a channel live. Identical on both probes.

No workaround was attempted. The only other Chicago-market path is Pluto's rotating-JWT
ad-stitcher, already refused on per-play-signing grounds in finding 23. **BACKLOG.**

### NASA TV — the DENY is still correct

`nasa-tv`'s NTV1 direct-HLS master serves 200 and advertises three rungs; **all three
404**. That is the original "NASA pattern" that motivated the master→variant probe, and it
confirms the standing `LineupSelector.DENY` / `WEB_DENY` entry and the channel's
honest-offline state are right. It was **not** pruned in this pass — it is honest-offline
by design, remains selectable in the picker, and is recorded here as a re-source candidate.

### NASA and EarthCam are BROADCASTER HANDLES — deliberately not relabelled

Both `iss-feed` (`@NASA/live`) and `earthcam-live` (`@earthcam/live`) point at a
**broadcaster's handle**, and a handle streams whatever that broadcaster is streaming.

At probe time `@earthcam/live` was titled *"EarthCam Live: Wrigley Field"*. It would have
been easy — and wrong — to convert `earthcam-live` into a dedicated Wrigley tile. The label
would be accurate for exactly as long as EarthCam kept that cam on the handle, and the
project would be making a claim its source cannot sustain. It also would **not** be the same
thing as the dedicated Wrigleyville cams, which finding 23 refused on hard grounds
(`Referer`-gated, rotating `t=/td=` token, yt-dlp extractor broken) — those remain
honest-no, and nothing here changes that.

`@NASA/live` was showing the official ISS stream at probe time, matching its existing "ISS
HD Live" label. Left as-is for the same reason, with the same caveat: it is the NASA
handle, not a dedicated ISS endpoint.

**Rule, recorded so a later pass doesn't re-tread it:** a channel sourced from a
`/@handle/live` URL is labelled for the *broadcaster*, never for whatever happened to be
on air during a probe.

---

## What changed in the repo

- `channels/seed.json` — +4 entries (52 → 56; 23 `hls` / 31 `youtube` / 2 `cspan`). The
  three restored feeds went back to their **original seed positions** so the diff reads as
  a re-source in place.
- `channels/category.py` — +4 slug→category entries. `cbs-news-247` → **US News**;
  `al-jazeera-en` / `cgtn-en` / `trt-world` → **Global News**. `CATEGORY_ORDER` is
  **unchanged**, which is what keeps the native app out of this change entirely.
- Tests — see below. The honest-no is recorded in `category.py`'s omissions block so a
  future pass finds the evidence next to the taxonomy rather than only in this file.

## Test coverage added (the previous coverage was near-vacuous)

An audit of the existing suite found that adding a channel was **almost entirely
unguarded**: only four assertions touched a new entry at all, and the change would have
gone green even if half the new channels never reached the database. Closed with:

- `test_api_channels_2026_08_fresh_sources` — each of the four by slug, with its expected
  `category` **and** `kind`, plus a guard that the three re-sourced feeds use an
  `@handle`/live URL and **never** a `watch?v=` video id.
- `test_every_seeded_channel_has_an_explicit_category` — every seeded slug must be
  explicitly mapped (one documented exception, `redbull-tv`), and the map may not keep
  entries for channels no longer shipped. The `GENERAL` fallback exists so an unmapped
  channel is never *hidden*; silently landing there is still a bug.
- `test_lineup_size_and_kind_breakdown_are_what_the_docs_claim` — pins 56 / 23-31-2 so the
  docs and the seed cannot drift apart silently, and names the files to update on a change.
- `test_shipped_channel_seed_is_wellformed_and_unique` + `…_seeds_every_channel` — the
  channel-seed equivalent of the long-standing feed-seed guards. **This closed a real
  latent gap:** `seed_from_file` and `override.seed_lineup` deliberately *skip* an entry
  whose validator rejects it (so one bad row can't stop the helper booting) — correct at
  runtime, but it meant a typo'd URL vanished **silently** and the channel simply never
  appeared. Asserting `stored == len(seed.json)` makes that loud at commit time.

Each new assertion was **mutation-tested**: dropping a channel, pinning a rotating video
id, seeding an uncategorised channel, and giving a channel a URL its validator rejects each
produce a red suite naming the exact problem.

---

## Reflection / do-not-re-tread

- A prune for **dead endpoints** is not a prune for **cause**. Before refusing to re-source
  something the project once carried, read *why* it went — the difference between "the host
  died" and "we judged this source unfit" decides whether a fresh endpoint is even eligible.
- **Master-200 is not liveness.** Two of eight candidates served a perfectly well-formed
  master whose entire ladder was 404. Any future sourcing pass must follow to a real
  segment; a manifest fetch alone would have added a permanently-dead tile.
- **A handle is a broadcaster, not a programme.** Resist labelling a `/@handle/live` entry
  for whatever is on screen during the probe.
- **Free-linear ad markers are not DRM.** SCTE-35 `#EXT-X-CUE-OUT` and a constant `?m=`
  cache-buster are ordinary for a FAST feed; the disqualifiers are `#EXT-X-KEY` and a
  *per-play* signature, and neither was present.
