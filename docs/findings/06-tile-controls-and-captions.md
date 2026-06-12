# Finding 06 — Tile controls (audio + captions) + lineup priority swap

> **Status:** Built + telemetry-verified away from the box (2026-06-04). The actual D-pad feel for the audio/captions interaction is **STAGED for the at-the-box finale** — built and unit-tested here, but the real-remote pass needs the operator's hands on the Onn remote.

## What this push does

1. **Captions are OFF by default on every tile.** `StreamPlayer` disables `C.TRACK_TYPE_TEXT` in the Media3 track-selection parameters before `prepare()` runs; the wall never auto-selects a soft caption track at startup.
2. **Per-tile controls overlay**: SELECT on a slot row in the side menu opens a small actions popup — **Channel** (re-enters the existing channel picker), **Audio**, **Captions**, **Close**. WyzeGrid-family styling; same focus model as the rest of the menu.
3. **Per-tile audio with a single-audible-tile model.** Selecting "Audio" makes the tile audible and **mutes every other tile** automatically; selecting it again on the same tile mutes the wall. The wall starts muted by default (audible slot index = `-1`), per the prompt's "wall of simultaneously-unmuted streams must never be the default" rule.
4. **Per-tile captions toggle** with honest "not available" surfacing when the stream has no soft text track. Toggling captions on a tile re-enables `C.TRACK_TYPE_TEXT` for that tile's player; toggling off disables it. The overlay shows `not available on this channel` when the stream has no track to toggle. **Burned-in captions** (pixels in the video — see the per-channel table below) are unaffected by this toggle and acknowledged honestly.
5. **Lineup swap**: Bloomberg TV + CNBC join the operator's preferred list at the top; DW News drops out of the default lineup but stays seeded for manual assignment via the menu picker.
6. **All preferences persist** via `LineupStore` SharedPreferences alongside the existing channel overrides:
   - `audible_slot` → `Int` (`-1` = wall muted)
   - `captions_on_slots` → JSON array of slot indices

## Per-channel caption table

The Operator's stated intent: **no subtitles/captions** (transcription text). Chyrons, lower-thirds, tickers, and broadcast graphics are NOT captions and stay as-is.

Mechanisms encountered (verified by fetching each master manifest and looking for `#EXT-X-MEDIA:TYPE=SUBTITLES` / `TYPE=CLOSED-CAPTIONS` lines):

| Channel | Mechanism | Toggle behaviour | Notes |
|---|---|---|---|
| **CBS Sports HQ** | Soft CEA-608 (`INSTREAM-ID="CC1"` on all 8 variants) | Toggle works | Off by default; operator can turn on per-tile. |
| **CNN** (slate feed) | No captions declared | Toggle is no-op → shows "not available" | The current seeded URL is the publicly-streamable slate feed (promotional / filler); the main CNN linear is paywalled behind Max. |
| **LiveNOW from FOX** | **Soft WebVTT track** (not burned-in on this CDN) | Toggle works | Folk knowledge says LiveNOW has a burned-in scrolling bar; that may apply to the Tubi / YouTube simulcast, not this Akamai feed. Default OFF means the bar isn't drawn. |
| **Newsmax** | No captions on the seeded `index.m3u8` URL | Toggle is no-op → shows "not available" | The sibling `master.m3u8` exposes a caption track, but the prober marks it `variant_http_404` so the channel goes unavailable. Honest trade-off documented: keep the channel playing (current state), lose the optional toggle. |
| **France 24 English** | No captions declared in this manifest | Toggle is no-op → shows "not available" | International HLS feed; captions/lower-thirds are broadcast graphics only, no transcription bar burn-in. |
| **Sky News** | No captions declared (likely a single-rendition variant playlist) | Toggle is no-op → shows "not available" | No burned-in transcription either — screen stays clean. |
| **BBC News** (worldwide shard) | No captions declared on `-ww-live` CDN | Toggle is no-op → shows "not available" | UK iPlayer feeds carry subs but require auth; the worldwide shard omits them. No burned-in transcription. |
| **DW News English** | Soft WebVTT (`DEFAULT=NO, AUTOSELECT=YES`) | Toggle works | Off by default per the controls overlay. |
| **Red Bull TV** | Not inspected (not in the prompt's investigation list) | — | — |
| **Bloomberg TV** *(new)* | Soft track expected (Phoenix-us feed declares CC) | Toggle works | Soft track — same off-by-default discipline as the rest. |

### What "burned-in captions" honestly mean

For any channel where transcription text is rendered into the video pixels, the controls toggle **cannot remove it** — the bytes are already in the picture by the time ExoPlayer decodes them. Two honest options for such a channel:

1. **Live with it** — the operator accepts the burn-in.
2. **Pick a different channel** — assign the slot to a channel without burn-in via the menu's channel picker.

**Out of scope by engineering default**: video post-processing (cropping the caption bar) — degrades the picture and may crop real content; logged to BACKLOG as an operator-decision item, **not built**.

## Lineup priority swap

Updated `LineupSelector.PREFERRED`:

```
2026-06-04:  [bloomberg-tv, cnbc, cbs-sports-hq, bbc-news, cnn, livenow-fox]
2026-06-03:  [             cbs-sports-hq, bbc-news, cnn, livenow-fox]
```

`FALLBACK` and `DENY` are unchanged. DW News English stays seeded but is no longer in `PREFERRED`; it falls into the rest-tier where the cycler picks it only if preferred + fallback don't fill the slots.

### Resolution result after this push

| status | count | channels |
|---|---|---|
| **live** | **10** | bbc-news, **bloomberg-tv** (new), cbs-sports-hq, cnn, dw-news-en, france24-en, livenow-fox, newsmax, redbull-tv, sky-news |
| unavailable | 9 | al-jazeera-en, c-span, cgtn-en, **cnbc** (new, no public HLS), cnn-international, iss-feed, nasa-tv, trt-world, white-house-tv |

### Clean-slate default lineup on a 4-slot wall (telemetry-verified)

```
slot-0 = bloomberg-tv   (PREFERRED #1 — new)
slot-1 = cbs-sports-hq   (PREFERRED #3 — cnbc unavailable, skipped)
slot-2 = bbc-news        (PREFERRED #4)
slot-3 = cnn             (PREFERRED #5)
```

`EV=TILE_READY` fired for each within 60 s of fresh launch. Logcat extract:
```
EV=TILE_READY|id=slot-0-bloomberg-tv
EV=TILE_READY|id=slot-1-cbs-sports-hq
EV=TILE_READY|id=slot-2-bbc-news
EV=TILE_READY|id=slot-3-cnn
```

The fifth preferred slug (`livenow-fox`) sits behind cnn and would fill slot 5 if N=5; it's still available for manual assignment via the menu.

## CNBC — honest "not found" (recorded permanently)

CNBC has **no free public HLS endpoint** as of 2026-06-04. The channel is paywalled cable; it doesn't run a free 24/7 FAST stream on Pluto, Samsung TV Plus, Roku Channel, or Plex (the major catalogs all carry zero entries). The August 2024 Warner DMCA wave took down most of the unofficial m3u8 mirrors. The seed entry is recorded with an `.invalid` placeholder URL — the prober marks it `dns_failure` correctly, the menu picker shows it as offline, the wall renders the honest OFFLINE panel if it's pinned to a slot. Honest unavailability per the prompt's "don't pretend it's removable" / "don't pretend it's resolvable" doctrine.

## What's staged for the at-the-box finale

- **Real-remote feel pass on the controls overlay.** The D-pad navigation works in the away-from-box telemetry layer (Compose focus on each `ControlRow`, focusable popups, BACK semantics), but the **felt experience** on the actual Onn remote — does the popup snap open / close cleanly, does the audio toggle make obvious sense, does the captions "not available" line read clearly from 10 feet — needs the operator there.
- The finale's checklist gains a single item: **"tile controls real-remote pass — verify audio toggle, captions toggle, channel re-pick navigate cleanly on the actual remote."**

## Standing rules at this push

- **unrelated host services: never touched.**
- **WyzeGrid** untouched on `.182` — the deploy was a `force-stop` + `install -r` + `am start`; WyzeGrid foreground service stays alive.
- **Helper redeployed** to `<USER>@<HOST>` per the standing standard (non-root, read_only, cap_drop ALL, dedicated bridge network — never any unrelated container on the host).
- Helper tests green: **136** unchanged.
- App test count: **~80+** (5 new — 4 `LineupStoreIntSetCodecTest` + 5 `LineupSelectorPreferredOrderTest`).
- TLS baseline from the prior commit (`b8240b7`) intact — the new app build still defaults to HTTPS 8443 with cert-pin trust.
