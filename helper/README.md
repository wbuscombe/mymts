# mymts-helper

Minimal NAS-side service. Aggregates news, resolves live-stream addresses. **Nothing else.**

Built and in use: RSS news aggregation, live-stream address resolution, and a markets / sports / news ticker — serving the TV app and the LAN web client. See the top-level [`README`](../README.md) and [`ARCHITECTURE.md`](../ARCHITECTURE.md).

## Quick start (local dev)

```bash
# from this directory
uv sync --extra dev          # installs runtime + dev deps into .venv
uv run pytest                # all tests must pass before any commit
uv run python -m mymts_helper  # starts the helper on :8091
curl -s http://127.0.0.1:8091/health | jq .
```

## Docker (the hardened-container shape)

`docker-compose.yml` runs the container the way the NAS does — non-root, `read_only` rootfs, all caps dropped, tmpfs `/tmp` — and it's a **one-command clone-to-running** path:

```bash
cp .env.example .env
docker compose up --build
```

brings up the helper on `http://localhost:8091` — migrations run on a fresh DB, `seed.json` loads, and `/health` + `/api/channels` + the web client at `/app/` all serve, **keyless** (channels + feed need no API keys). Because the rootfs is read-only the helper writes its SQLite state to a **writable named volume mounted at `/data`** (the Dockerfile chowns `/data` to uid 10001 so a fresh volume is writable with no host-side step). `docker compose down` keeps the volume; `down -v` wipes it for a true fresh first-run.

- For a no-Docker local run, the **`uv run` path above** also works (per-user data dir, zero config).
- The operator's production deploy uses the separate named-volume compose at [`deploy/docker-compose.nas.yml`](deploy/docker-compose.nas.yml) (HTTPS on `:8443`, host bind mounts) — not this file.

## Phantom (demo / offline) mode

Set `PHANTOM_MODE=1`. Phantom mode preloads deterministic fixtures — feed items, a sample sports slate, and the seeded channels marked live — and replaces the network resolver so the helper makes **zero outbound calls** (a hard contract). Fixtures live under `tests/fixtures/`; the inventory is in [`../.phantom.yml`](../.phantom.yml).

## Standards followed

- Non-root container, pinned image tag (digest pin → Stage 6).
- All capabilities dropped, `read_only` rootfs, `no-new-privileges`, tmpfs for `/tmp`.
- Structured JSON logging to stdout with a paranoid redaction pass.
- No secrets baked in. Build args (`BUILD_SHA`, `BUILD_VERSION`) are not secrets.
- `pyproject.toml` is the single source of truth; deps are pinned to exact versions.
- HEALTHCHECK uses `/health` so a stuck process is detectable by Docker + the operator's monitoring.

## API endpoints (LAN-only)

All JSON responses carry `schema_version` (additive-only). Served on the helper's LAN address; never publicly exposed.

| Endpoint | Returns |
|---|---|
| `GET /health` | liveness + feeds/channels freshness snapshot |
| `GET /api/feed` · `GET /api/feed/sources` | newest-first plain-text feed items · source inventory |
| `GET /api/channels` | channel lineup (`current_url` only when `status==live`) |
| `GET /api/ticker/markets` · `GET /api/ticker/sports` | real-or-SAMPLE markets · sports + per-sport cards |
| `GET /api/presets` | server-authoritative wall presets (News Wall / Nature / Space / Chill / Mixed / Ocean / Eagles); `default` = `news` |
| `GET /api/playlist.m3u` | the **`default`** profile (every live channel) as an M3U playlist |
| `GET /api/playlist/{name}.m3u` | a named **profile** (ordered channel subset); 404 if unknown |
| `GET /app/` | the LAN web client (static; only when `WEB_CLIENT_DIR` is set) |

The M3U endpoints point at each channel's **resolved upstream URL** — the helper resolves/shields but never proxies the video (no-proxy). Only live channels are listed. Profiles are a built-in `default` plus optional operator-defined named profiles from `PROFILES_FILE` (see [`profiles.example.json`](profiles.example.json)), read once at startup — operator data kept out of git, *not* the TV's mutable lineup (that stays on-device).

## What the helper does NOT do

- Hold the operator's **mutable** lineup/presets. Those live on-device in the TV app (`LineupStore`). *(The optional `PROFILES_FILE` is static, read-only-at-startup channel-selection config for the playlist endpoint — not the TV's mutable state.)*
- Expose any public HTTP surface. It is internal-to-the-NAS.
- Touch any unrelated container on the host. Standing rule.
- Log secrets, internal IPs, or absolute paths beyond `/app`.
