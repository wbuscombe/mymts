# tools/screenshots

Headless capture of the MyMTS **demo** web wall for the docs gallery. Runs only against the
**phantom-mode** helper (mock/SAMPLE data, no NAS, no secrets, no real upstreams), so it's
reproducible and safe to run anywhere — locally or in CI.

- `capture.mjs` — drives the LAN web client at `/app/` and writes the gallery PNGs to
  `docs/screenshots/web/`.
- `make-placeholders.mjs` — regenerates the labeled `docs/screenshots/device/` hero-shot
  placeholders (the operator replaces those with real native-TV captures).

Playwright is **pinned** (`package.json` + `package-lock.json`, committed); `node_modules`
and the downloaded browser are not committed.

## Run it

```bash
# 1. helper in demo mode (separate terminal):
cd helper && PHANTOM_MODE=1 PORT=8091 uv run python -m mymts_helper

# 2. install the pinned tooling + the browser, then capture:
cd tools/screenshots
npm ci                              # installs the pinned Playwright
npx playwright install chromium     # downloads the matching Chromium
HELPER_URL=http://127.0.0.1:8091 node capture.mjs
```

Env knobs: `HELPER_URL` (default `http://127.0.0.1:8091`), `OUT_DIR` (default
`docs/screenshots/web`).

## What it captures

`wall-overview`, `ticker-markets`, `ticker-sports`, `ticker-news`, `settings`,
`channel-picker` — see [`../../docs/screenshots/README.md`](../../docs/screenshots/README.md)
for what each shows and the honest notes on demo data.

## In CI

`.github/workflows/screenshots.yml` (manual `workflow_dispatch`) runs the same flow on a
runner and uploads the result as a build **artifact** — it does not auto-commit binaries.
