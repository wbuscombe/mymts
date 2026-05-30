# mymts-helper

Minimal NAS-side service. Aggregates news, resolves live-stream addresses. **Nothing else.**

Stage 1: skeleton with `/health` only. Aggregation + resolution land in Stage 2.

## Quick start (local dev)

```bash
# from this directory
uv sync --extra dev          # installs runtime + dev deps into .venv
uv run pytest                # all tests must pass before any commit
uv run python -m mymts_helper  # starts the helper on :8091
curl -s http://127.0.0.1:8091/health | jq .
```

## Docker

```bash
docker compose build
docker compose up -d
docker compose logs -f
docker compose down
```

The compose file is local-only. The NAS deploy file lands with Stage 2 once the helper does real upstream work.

## Phantom (demo / offline) mode

Set `PHANTOM_MODE=1`. Stage 1 has nothing real to mock, so phantom mode currently just flips a flag in `/health`. Stage 2 fixtures will live under `tests/fixtures/`; the inventory is in `.phantom.yml`.

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
- Touch the unrelated host container. Standing rule.
- Log secrets, internal IPs, or absolute paths beyond `/app`.
