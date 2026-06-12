# MyMTS

> Ambient news video wall — a native Android TV app for the Onn 4K box, paired with a minimal NAS-side helper. Your channels, your wall, on-by-default, calm from the couch.

**Status:** Built — Android TV app + Python helper + LAN web client are implemented and in use (markets/sports/weather, the configurable wall, the menu). See [`CHANGELOG.md`](CHANGELOG.md).

## Quickstart (clone → running locally, no secrets)

```bash
# 1. Helper in demo mode — mock data, zero network egress, no NAS/secrets:
cd helper && PHANTOM_MODE=1 PORT=8091 uv run python -m mymts_helper
#    → http://localhost:8091  (web client at /app)

# 2. TV app (Android TV emulator running), pointed at the local helper:
./gradlew :app:installDebug
adb shell am start -n com.mymts/.MainActivity --es helper "http://10.0.2.2:8091"
```

Full walkthrough (prerequisites, the fuller real-public-data path, troubleshooting):
[`ONBOARDING.md`](ONBOARDING.md). Prefer your AI assistant to set it up? Feed it
[`docs/onboarding/ONBOARD-01-SETUP.md`](docs/onboarding/ONBOARD-01-SETUP.md).
Tests: `cd helper && uv run pytest` · `./gradlew :app:testReleaseUnitTest`.

---

## What this is

MyMTS is a self-hosted, personal alternative to monitor-the-situation.com, built around one principle: **it can never become a path into the home network**. It is *ambient first* (running on a TV in the background), *occasionally active* (the operator picks up the remote when something is happening), and *built for the couch*, not the desktop.

It is a **native Android TV application** (Kotlin + Jetpack Compose for TV + Media3/ExoPlayer) paired with a **minimal NAS-side helper service** that does only the two jobs the TV must not — aggregating news sources and resolving live-stream addresses. No browser engine is in the critical path.

For *why* native — and what the previous web-app round taught us — see [`docs/foundation/04-TECHNICAL-APPROACH.md`](docs/foundation/04-TECHNICAL-APPROACH.md).

## Read this first

Before touching code or filing issues, read the foundation docs in order:

1. [`docs/foundation/01-VISION.md`](docs/foundation/01-VISION.md) — what MyMTS is. The fixed point.
2. [`docs/foundation/02-TRUST-BAR.md`](docs/foundation/02-TRUST-BAR.md) — security, privacy, stability principles. Every one ranked and traceable.
3. [`docs/foundation/03-OPERATIONAL-BAR.md`](docs/foundation/03-OPERATIONAL-BAR.md) — delivery, deployment, maintenance outcomes.
4. [`docs/foundation/04-TECHNICAL-APPROACH.md`](docs/foundation/04-TECHNICAL-APPROACH.md) — the architecture decision and the mechanism map.
5. [`docs/foundation/00-READING.md`](docs/foundation/00-READING.md) — engineer's anti-drift restatement, including the precedence order.

The engineering brief that operationalizes those is [`docs/BUILD-PROMPT.md`](docs/BUILD-PROMPT.md).

## Precedence (when principles tension)

1. **Security** — never a path into the home network.
2. **Shared-friend safety** — wins over the operator's convenience and own data.
3. **Data durability** — wins over everything below.
4. **Honest staleness** — wins over visual polish.
5. **Feed-level resilience** — lowest; a dead tile is forgivable.

Cross-cutting: **one-click-easy content**. Routine content changes never ride the deploy path.

## Architecture (the short version)

Two pieces, clean boundary:

- **The TV app (the wall).** Native Android TV, Onn 4K. Renders feed as native text, plays video in the native player, owns all operator interaction, persists operator content on-device.
- **The helper (back-of-house).** Minimal NAS-side service. Aggregates news (concentrating all hostile-input handling) and resolves live-stream addresses (isolated, egress-bounded). Nothing else.

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for the full architecture + mechanism map.

## Status

**Built and in use.** The TV app (video wall + feed + ticker), the NAS helper (channels / feed / markets / sports / news), and the LAN web client are all implemented and running on the Onn 4K box. The original Stage 0–5 build plan is complete; operational hardening (the original Stage 6–7 — rollback, CI, alerting, docs) is ongoing — see [`docs/adversarial-review-2026-06.md`](docs/adversarial-review-2026-06.md) for the current backlog and [`CHANGELOG.md`](CHANGELOG.md) for history.

## Setup

See the **Quickstart** above to clone → run locally in demo mode (no secrets, no NAS). Full walkthrough: [`ONBOARDING.md`](ONBOARDING.md).

## Standards

Conventional commits (`feat/fix/docs/chore/test/security`), semver with tagged releases, MIT licensed, no secrets in code or logs, signed installs, pinned dependencies, single-command test runners (`cd helper && uv run pytest` · `./gradlew :app:testReleaseUnitTest`). See [`CONTRIBUTING.md`](CONTRIBUTING.md) and [`SECURITY-PRACTICES.md`](SECURITY-PRACTICES.md).

**AI agents working on this repo: read [`AGENTS.md`](AGENTS.md) first** — it codifies the protected invariants, the never-without-approval list, and the deploy (adb) invariant.

## Hard constraints

- **Never touch unrelated services on the helper's host.** The helper runs isolated, on its own network, and never reaches into anything else sharing its host. Standing rule.
- **No browser engine in the content path.** Feed content is native text; video is the native player.
- **No third-party telemetry.** Nothing about what the operator watches leaves operator infrastructure.

## License

MIT. See [`LICENSE`](LICENSE).
