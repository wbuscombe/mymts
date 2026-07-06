# 0004 — Unified channel registry: one lineup for every surface

**Status:** accepted (2026-07-06) · helper / web / app (native)

Kills the per-surface split introduced when weather radar shipped web-only. The channel
registry is now **unified**: `GET /api/channels` serves the SAME lineup — video channels
AND the non-video WIDGET sources (the NWS radar loops) — to every consumer (native TV
picker, web `/app/`, `/control/`), with **no membership-changing query param**. A channel's
`kind` tells a renderer HOW to render it; it never gates WHETHER a surface lists it. The
native app learned to render the radar loop in a tile, and a cross-surface parity test locks
the registry so a surface cannot silently drift from the others again.

## Context

Radar was originally shipped web-only (decision-free, 2026-06-27): the helper listed the
`weather-radar-*` pseudo-channels only when the web client sent `GET /api/channels?widgets=1`;
the native TV picker fetched without the param and never saw them. The rationale at the time
was "the native app can't render a non-video widget, so don't surface a non-playable entry on
the TV." The cost: the native wall was **silently missing** channels the web wall offered —
exactly the "web got X, native didn't" drift that erodes trust in the lineup. The right fix is
not to keep hiding radar from native; it is to teach native to render it and to make the
registry a single source of truth for all surfaces.

## Decisions

1. **One registry, every surface. Remove the `?widgets=1` gate.** `list_channels()` appends the
   radar widget entries unconditionally. The `widgets` query param is gone; a stale `?widgets=1`
   from a cached older web client is now an unrecognized (ignored) param — the response is
   byte-identical. Parity is the default, not an opt-in.

2. **`kind` distinguishes HOW to render, never WHETHER to list.** A per-channel `kind` stays
   (`hls` / `youtube` / `cspan` = video; `weather-radar` = widget). A named **widget-kind**
   concept (`weather.regions.WIDGET_KINDS` / `is_widget_kind`, mirrored by native
   `Channel.isWidget` and the web) drives render-branching and toggle-hiding. Future widget kinds
   (a metar/alerts card, say) join the set and are listed everywhere for free.

3. **Native renders the radar loop.** A new `TileSlotResolver.Slot.Radar` renders the
   helper-proxied NWS RIDGE loop as an animated GIF via **Coil** (`GifDecoder`, which animates on
   the Onn box's minSdk-23 floor) — not ExoPlayer. The radar channel's relative
   `/api/weather/radar/<region>` proxy path is resolved against the helper base into an absolute
   image URL; the tile pulls a fresh scan every ~5 min (cache-bust to a warm region-keyed helper
   cache — NWS is not re-hit), mirroring the web tile. A widget tile has no audio, no captions, no
   LIVE badge, and no per-tile Audio/Captions/Reconnect controls — its honesty is the `<img>` load
   itself (the helper already serves last-good-stale frames, so a refresh almost always yields a
   real, if stale, image rather than an error).

4. **Radar stays an explicit per-cell pick.** Radar reports `status=live`, so it now appears in
   the native `playable` set — but `LineupSelector` excludes `weather-radar-*` slugs from the
   auto-filled default/top-up grid (matching the web's `isRadarSlug` exclusion). An operator
   assigns radar from the picker; it never auto-fills a video slot.

5. **Honest capability degradation is "listed, rendered differently" — never "silently omitted."**
   The one place a widget legitimately cannot appear is the M3U playlist export (`/api/playlist.m3u`
   for VLC/IPTV): an animated-image widget has no stream URL to hand an external player. That
   omission is made explicit (an `is_widget_kind` guard + a comment), and it is a *stream export*,
   not a picker surface — every picker still lists radar.

6. **A cross-surface parity contract test.** `scripts/check_channel_parity.py` (CI) asserts the
   three surfaces' section taxonomies are the identical ordered list (helper `CATEGORY_ORDER` ==
   web `CHANNEL_CATEGORY_ORDER` == native `ChannelCategory.ORDER`) and that no surface carries a
   widget gate. Per-surface unit tests assert a radar widget survives each surface's list-building.
   This is the structural guard that prevents a future recurrence of the split this decision closes.

## Consequences

- Native picks up a new dependency (Coil + coil-gif) — the first non-ExoPlayer render path on the
  wall. A native change ⇒ version bump (**v0.4.0**, versionCode 400) + tag + signed APK per the
  release discipline.
- The native app must know the helper base URL to absolutize radar's relative proxy path — it
  already does (it fetched `/api/channels` from there), so `WallScreen` threads `helperBaseUrl`
  into `TileSlotResolver.resolve`.
- Adding a future widget kind is now a helper-only registry change that appears on every surface;
  each surface's renderer only needs a branch for the new `kind`, guarded by the parity test.
