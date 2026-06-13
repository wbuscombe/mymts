# 22 — Playlist / M3U endpoint + profile foundation (Campaign 3 HALF 2, 2026-06-13)

The helper now serves the resolved channel lineup as a standard M3U playlist, so a generic player (VLC, incl. VLC-on-Apple-TV) can consume MyMTS's channels directly. This is the scoped *start* of the cross-platform-profiles fork (BACKLOG item H) — a multi-profile backend with VLC as one client — not a finished multi-tenant system.

## What shipped

- **`GET /api/playlist.m3u`** — the built-in `default` profile: every channel that is **live now**.
- **`GET /api/playlist/{name}.m3u`** — a named profile: an ordered channel subset; unknown name → 404.
- Both return `audio/x-mpegurl`: `#EXTM3U` then, per channel, `#EXTINF:-1 tvg-id="<slug>" tvg-name="<label>",<label>` + the channel's resolved upstream URL.
- A minimal **profile** abstraction (`playlist/profiles.py`): built-in `default` (= all live) + optional operator-defined named profiles from a JSON file at `PROFILES_FILE` (`helper/profiles.example.json` shows the shape), read once at startup.

Files: `helper/src/mymts_helper/playlist/{__init__,m3u,profiles,api}.py`; wired in `app.py`; `Config.profiles_file` (env `PROFILES_FILE`); tests `tests/test_playlist_{m3u,profiles,api}.py`.

## Channel coverage for VLC (vs. the browser)

The playlist lists every channel with `status==live` — whatever the prober currently resolves. **VLC is a more capable client than the LAN web grid:** it plays plain-`http://` HLS, so the `browser_playable` mixed-content hint (which gates the *web* grid) is **not** used to filter the M3U — a channel that's TV-only in the browser (http sub-resources) is still fine in VLC. So the M3U is the native TV wall's resolvable set, a potential superset of what the web client can play. (CORS, a browser-only concern, is likewise irrelevant to VLC.)

Honest inclusion holds: a channel that doesn't resolve — DNS/403/404/manifest-reject, or simply not-yet-probed (`unknown`) — is omitted, never listed as a working endpoint. A fully-down lineup is a valid empty `#EXTM3U`.

## No-proxy preserved

Each `#EXTINF` points at the channel's probed `current_url` (the URL the TV plays), NOT a helper-relayed path. The helper stays the resolver/shield and never enters the video bytestream (the no-proxy decision). The M3U is a channel *list*, not a gateway; no new egress, no new fetch (it reads the snapshot the prober already maintains).

## Profiles — foundation vs. future

- **Laid:** the backend shape — a named profile → channel-selection → per-client playlist. Different displays can get different channel sets (office wall vs. a bedroom Apple TV) via `/api/playlist/{name}.m3u`.
- **Stateless + read-only:** the endpoint returns what's live now, narrowed/ordered by the profile. Profiles are loaded once at startup from operator config; the loader is tolerant (bad/again-bad file → default-only, so the wall boots), reserves the `default` name for the built-in, and validates names/slugs with the channel slug rule.
- **Not the TV's lineup:** the profiles file is optional, static, read-only-at-startup operator config (like `seed.json`), distinct from the TV app's mutable on-device `LineupStore`.
- **Still the open item-H decision (not presupposed):** per-client server-side *prefs* (audio/caption/layout), identity, and cross-device *sync* — device-local vs. helper-hosted. The remote/public web client stays a separate deferred-security item.

## Tests (TDD)

26 new tests, full helper suite **253 green**, new code ruff-clean:
- `test_playlist_m3u` — render shape, ordering, live-only gate, no-URL skip, label newline/quote injection neutralized, control-char URL skip, empty→`#EXTM3U`.
- `test_playlist_profiles` — loader (object + bare-list forms; missing/malformed file → default-only; invalid entries skipped; reserved `default` ignored) and selection (default = all live; named subsets/reorders; offline/unknown slugs dropped).
- `test_playlist_api` — the **environment-invariant** test: against a real seeded SQLite DB, an `unavailable` channel is **absent** while a `live` one is present (exercises the live gate, not just parsing); the **no-proxy** property (stream lines are the upstream URL, no `/api/` path); per-profile subset/order; unknown profile → 404; honest empty when nothing live. (Built with a non-context-manager `TestClient` so the prober lifespan never overwrites the channel states the test sets — the deterministic way to assert live/not-live without the network.)

## Operator validation (post-deploy — the parts that can't be unit-tested)

Helper redeploy at the NAS (`docker compose build --pull && up -d`; verify `/health` `build_sha`). Then:
1. `curl https://<helper>:8443/api/playlist.m3u` → valid `#EXTM3U` with the live channels' upstream URLs.
2. Load that URL in desktop VLC → channels appear → a clean-resolving channel plays.
3. VLC on the Apple TV → add a network stream → paste the URL → confirm. (The real end-to-end check; the Apple-TV hardware step is the operator's.)
4. (Optional) point `PROFILES_FILE` at a copy of `profiles.example.json` (real slugs), redeploy, and confirm `/api/playlist/<name>.m3u` returns just that profile's live channels in order.

## Sequencing / status

Foundation laid; the full multi-profile UI, per-client prefs-sync, and Apple-TV polish are item-H future work. Deploy + the VLC/Apple-TV checks above are the operator's. CHANGELOG (2026-06-13), `ARCHITECTURE.md §23`, `docs/THREAT-MODEL.md` (playlist claim section), BACKLOG item H (updated).
