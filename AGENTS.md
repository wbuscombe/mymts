# AGENTS.md — guardrails for AI agents working on MyMTS

**Read this before touching anything.** This is the canonical in-repo standard for any coding agent working on MyMTS. It is derived from the 2026-06 adversarial review and the project's protected invariants. If a request conflicts with this file, stop and surface the conflict rather than guessing.

MyMTS is a **solo homelab ambient news wall**: a native Android TV app (Kotlin/Compose for TV, Media3/ExoPlayer) on an Onn 4K box, a Python/FastAPI helper in a hardened Docker container on a NAS, and a LAN web client served by the helper at `/app`. Severity is capped by that reality — there is no multi-tenant, no PII, no inbound-internet surface, no on-call. A "P0" here means *the wall is broken* or *a real secret/topology leak* — nothing else.

---

## Preserve (protected invariants — do NOT "fix" or "improve" these)

- **Locked panel-fit:** Fit scale **80%** / Vertical stretch **110%** / Overscan **None** / Position **0,0**. Panel-specific and deliberately locked. It persists through `LineupStore`/`WallSettings` keys — any settings change must round-trip it intact.
- **Manual-launch-on-boot.** The kiosk holds once launched; wall-on-boot was empirically abandoned. Do NOT re-pursue device-owner / lock-task / boot auto-foreground.
- **Honest-degradation discipline.** SAMPLE / STALE tags, play-what-works, `is_sample` defaults to *true*, channels mask `current_url` unless `status==live`, "scores unavailable" / "no games" are *real* states. **Never hide the not-live state** to make the wall look healthier.
- **Same release-signing key, always.** Gitignored, never regenerated; `IS_RELEASE_SIGNED` is the gate. A debug-signed APK must never reach a real device.
- **The wire schemas the app + web consume:** the markets DTO, the `GameDTO` + per-sport `SportCardDTO` (`kind`-dispatched), and the channel/feed/ticker schemas. They are additive-only and pin `schema_version`.

## Never without explicit per-session approval

- **Touch the PIA VPN container / any `service:vpn` config.** The helper is NOT behind PIA, but the NAS runs PIA for other things. Untouchable.
- **Touch the gitignored local config or secrets:** `local.properties`, `scripts/deploy.local.env`, `docs/ops-local/`, `app/keystore.properties`, `*.jks` / `*.key`. These hold the operator's real values and stay out of git.
- **Force-push or rewrite git history.** Default is no. The *one* authorized, bounded exception follows the protocol in the house `professionalize.md` §0 (backup-mirror first → rewrite on a throwaway clone → verify gone-from-blobs-AND-messages + secrets-absent + DAG-intact → human checkpoint → `--force-with-lease` only → the rule resumes → coordinate collaborator re-clone).
- **Background `adb`** (background adb tasks have caused multi-hour device hangs), or **`adb kill-server`** (would disrupt the *other* Onn boxes `.182`/`.158`). One foreground adb op at a time.
- **Relax the helper container hardening** (`read_only`, `cap_drop ALL`, non-root uid 10001, `no-new-privileges`) to make something write — use a narrow named volume instead.
- **Add a release-signing job to CI**, or put the keystore anywhere shared. CI stays test/lint-only.

## Sensitive areas (read the lore before editing)

- **The `.92` Wi-Fi adb transport** wedges and can truncate a push. See `docs/ops-local/OPERATIONS.md` for the truncating-push / 0-byte / streamed-install-deadlock failure modes and device-specific reconnect recovery. Use the deploy invariant below; never improvise.
- **The Compose-for-TV focus model** (`ui/nav/WallFocusModel.kt`) — a pure `(focus, intent, counts) -> NavResult` function with ~49 invariant tests. Keep it pure; focus-escape on overlay dismiss is historically finicky. Add a test for any change.
- **The pollers** (`helper/.../ticker/pollers.py`, `feeds/poller.py`, `channels/prober.py`) hold the degradation logic (HTTP-status handling, `real_as_of`/stale gating, per-source isolation, keep-prior-snapshot) and currently have **no direct tests**. Handle carefully; do not "simplify" a degradation branch without adding coverage.
- **The TLS pin** — `network_security_config.xml` is generated at build time from the helper URL (commits no topology). Don't hardcode a host into it.

## Before code changes

- Read before writing. Identify the files likely to change, the risks, the tests to add/run, and the rollback path.
- Confirm you're in the **dev checkout** (you are) — never run git operations against a deploy copy.
- Know how the change reaches the target: **the helper deploy rebuilds** (`docker compose build --pull && up -d`, never a bare restart), and **the app deploy uses the adb invariant** below (never a streamed `adb install`).

## After code changes

- Summarize the changed files, the tests run **with their output**, the manual verification done, and any unresolved risk. State plainly if something failed or was skipped.
- For a settings-touching change, manually toggle the knob → force-stop → relaunch → confirm it persisted **and the locked panel-fit is intact**.

## Secrets

- Never print, echo, `cat`, log, commit, or invent a secret. Reference by variable name. Real values go only in the gitignored local files; only `.example` templates are tracked. Pre-commit gitleaks is the backstop, not the plan.

## Dependencies

- Pin versions; commit lockfiles (`uv.lock`, the Gradle version catalog). Justify any new dependency. Security-only patch bumps go in their own `security:` commit, verified green.

## Testing

- Add/update tests for any changed behavior, **including negative and error paths**. Don't delete a test without an equal-or-better replacement.
- **The v5 environment-invariant rule:** for any fix whose correctness depends on the production environment shape (Docker vs host filesystem, container uid / read-only `/data`, the cert-pin host resolution, the adb transport, the NAS mount topology, the poller process topology), the regression test must **exercise that environment invariant** — not pass under a single-filesystem `tmp_path` or a mocked-green upstream. A green test on captured bytes proves parsing, not reachability.
- Run `cd helper && uv run pytest` (helper) and `./gradlew :app:testReleaseUnitTest` (app) before committing.

## Deploy — the adb invariant (canonical; the deploy scripts must conform to this)

The **app** deploy is, in order, and with each gate enforced:

1. `adb push <apk> /data/local/tmp/mymts.apk` — **never** a streamed `adb install`.
2. **Verify the on-device byte size equals the local APK** (macOS host `stat -f%z` vs Onn toybox `stat -c %s`); **abort** if they differ — that's a truncated transfer over the flaky link.
3. `adb shell pm install -r /data/local/tmp/mymts.apk`.
4. **Verify `lastUpdateTime` advanced** (`dumpsys package com.mymts | grep lastUpdateTime`).
5. **One foreground adb op at a time.** Never background adb. On a wedge, do a device-specific reconnect — **never `adb kill-server`** (it disrupts the other Onn boxes).

The **helper** deploy rebuilds the image (`docker compose build --pull && up -d`) and verifies the running container's `/health` `build_sha` matches the deployed SHA — a bare restart never picks up code changes.

## Definition of Done — closing a major phase

A major development phase is **not complete** until all three hold:

1. **Implemented + green.** The feature/code is done and ALL tests pass (`cd helper && uv run pytest` + `./gradlew :app:testReleaseUnitTest`) — zero flaky, zero expected-fail.
2. **Professionalization run as the closing step.** Run the house `professionalize.md` protocol — which now includes its **§6 Documentation Audit & Update**: audit the docs against the *current* state for drift (README / CHANGELOG / ARCHITECTURE / run-paths / links), **regenerate the screenshot gallery + verify each shot against the live UI** (a UI rebuild silently breaks the capture's navigation — fix the capture script first, then regenerate), cut the dated CHANGELOG version on a release, and fix the currency minors. Proportional (deltas only), catch false flags, honest caveats over papering-over, **docs-only `git diff`** (code fixes are a separate task), and end on a docs **sign-off**.
3. **Shipped clean.** Conventional bisectable commits; docs-hygiene + secret-scan green; pushed (and the helper/app deployed where the phase warrants, per the Deploy invariant above).

**Corollary (self-enforcing):** every major MyMTS feature prompt ends with this professionalization + documentation-audit closing phase — an agent that finishes the feature but skips the docs audit has **not** finished the phase. The RITUAL companion is `docs/PHASE-END-CHECKLIST.md`; the ENFORCED backstop is the `MAINTENANCE-CHARTER.md` docs-hygiene CI gate.

## Scope & uncertainty

- Make the **smallest safe change**. Don't refactor unrelated code "while you're in there."
- This is a solo homelab display: no auth system (no users), no enterprise observability/paging, no multi-region. Don't scaffold them.
- When uncertain about an invariant, a deploy step, or whether something is load-bearing — **ask**, or surface it as a flagged item. Cap severity by the blast radius; tag runtime/external claims you can't verify from the repo as such.

---

*The evidence behind each guardrail came from the 2026-06 adversarial review (a one-off operator deliverable, not kept in-repo); the house `professionalize.md` is the cross-project standard. `MAINTENANCE-CHARTER.md` is the continuous-quality platform — the ENFORCED CI checks + the RITUAL `docs/PHASE-END-CHECKLIST.md` an agent runs when closing a phase; add a check there whenever an audit finds a new gap class.*
