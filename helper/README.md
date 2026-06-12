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

`docker-compose.yml` runs the container the way the NAS does — non-root, `read_only` rootfs, all caps dropped, tmpfs `/tmp`. Because the rootfs is read-only the helper needs a **writable data volume** for its SQLite DB, and the bare compose here does **not** mount one — so `docker compose up` on it is **not** a one-command demo (it will fail to create the DB and crash-loop).

- For a quick local run, use the **`uv run` path above** (the supported demo path).
- The production deploy uses the named-volume compose at [`deploy/docker-compose.nas.yml`](deploy/docker-compose.nas.yml).

## Phantom (demo / offline) mode

Set `PHANTOM_MODE=1`. Phantom mode preloads deterministic fixtures — feed items, a sample sports slate, and the seeded channels marked live — and replaces the network resolver so the helper makes **zero outbound calls** (a hard contract). Fixtures live under `tests/fixtures/`; the inventory is in [`../.phantom.yml`](../.phantom.yml).

## Standards followed

- Non-root container, pinned image tag (digest pin → Stage 6).
- All capabilities dropped, `read_only` rootfs, `no-new-privileges`, tmpfs for `/tmp`.
- Structured JSON logging to stdout with a paranoid redaction pass.
- No secrets baked in. Build args (`BUILD_SHA`, `BUILD_VERSION`) are not secrets.
- `pyproject.toml` is the single source of truth; deps are pinned to exact versions.
- HEALTHCHECK uses `/health` so a stuck process is detectable by Docker + the operator's monitoring.

## What the helper does NOT do

- Hold the operator's lineup/presets/sources. Those live on-device in the TV app.
- Expose any public HTTP surface. It is internal-to-the-NAS.
- Touch any unrelated container on the host. Standing rule.
- Log secrets, internal IPs, or absolute paths beyond `/app`.
