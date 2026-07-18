# Contributing

> **Status: active.** The toolchain is built and operational. The fastest way
> from a clone to a running wall is [`ONBOARDING.md`](ONBOARDING.md) (demo mode,
> no secrets). Build/test commands are below and in the README; CI enforces them
> on every push (`.github/workflows/ci.yml`).

## Read this first

The vision + trust posture (when in doubt, these win):

1. `docs/foundation/01-VISION.md`
2. `docs/foundation/02-TRUST-BAR.md`
3. `docs/foundation/03-OPERATIONAL-BAR.md`
4. `docs/foundation/04-TECHNICAL-APPROACH.md`
5. `docs/foundation/00-READING.md` — anti-drift restatement + precedence order.

Then the engineering brief: `docs/BUILD-PROMPT.md`, and [`ARCHITECTURE.md`](ARCHITECTURE.md) for how the pieces fit.

The operational guardrails every contribution must honor (load-bearing — read them):

- [`AGENTS.md`](AGENTS.md) — the protected invariants + the deploy-safety rules (e.g. the adb deploy invariant; never touch the unrelated VPN container on the host).
- [`MAINTENANCE-CHARTER.md`](MAINTENANCE-CHARTER.md) — the continuous-quality platform: the **ENFORCED** `docs-hygiene` CI gate (no topology / personal-config leakage in public docs) + the **RITUAL** layer.
- [`docs/PHASE-END-CHECKLIST.md`](docs/PHASE-END-CHECKLIST.md) — the judgment checks to run when closing a phase (docs-vs-reality, screenshots, fail-safe contracts, …).

## Commits

- **Conventional commits.** Types: `feat`, `fix`, `docs`, `chore`, `test`, `security`, `refactor`, `perf`, `build`, `ci`.
- Scope by the component you touched where it helps: `feat(helper): …`, `feat(web): …`, app changes usually unscoped. For a change spanning components, scope to the primary one and note the rest in the body.
- One logical change per commit. The body explains *why*, not what (the diff explains what).
- `BREAKING CHANGE:` footer required for any change that breaks the operator's stored data or the helper API contract.

## Branching + releases

- `main` is the long-lived branch. Stage milestones land in `main`.
- Per-stage work happens in branches named `stage-N/<short-slug>`. Each ends green (tests passing) before merge.
- Releases are **semver** and **tagged**. **`v0.1.1`** is the first tagged release — the launchable desktop executable (a multi-OS GitHub Release; see `CHANGELOG.md`).

## Tests

- TDD as the default. Critical paths and the trust-boundary behaviors **must** have tests.
- No flaky or expected-to-fail tests in the suite. Tests run green before any commit and before any release.
- Run them locally before opening a PR (the same suites CI runs on every push):
  ```bash
  cd helper && uv run pytest                        # helper: pytest + the zero-egress phantom contract
  ./gradlew :app:testReleaseUnitTest                # native app: JVM unit tests
  node --test web/test/*.test.mjs                   # web client: pure render/honesty logic (no deps)
  python -m unittest discover -s renderer -p 'test_*.py'  # renderer: supervisor + run + fan-out (pure)
  ```
- The **web client** is served by the helper at `/app` (LAN-only, credential-free, same-origin); its honesty/render logic is unit-tested above and its DOM is verified against demo mode. See [`web/README.md`](web/README.md). The full walkthrough for running everything is [`ONBOARDING.md`](ONBOARDING.md).

## Code style

- `.editorconfig` is authoritative.
- Kotlin: 4-space indent, max line 120; idiomatic Compose; no `!!` on platform types from Java interop.
- Python (helper if Python-based): 4-space indent, max line 100; type hints; `ruff` + `mypy` strict.
- Comments explain **why** something is the way it is, not what it does. The diff and the names explain what.

## Security practices (every contribution honors these)

- **Never** commit secrets, tokens, credentials, API keys, or absolute paths beyond the project root.
- `.env.example` template lives in the repo; the real `.env` does not.
- Never echo or print a secret to stdout — write it directly to the file or pass by env var reference.
- Never reach into unrelated services sharing the helper's host; keep the helper isolated on its own network.
- See `SECURITY-PRACTICES.md` for the full list.

## Documentation

- Update `README.md`, `CHANGELOG.md`, and any affected docs **in the same commit** as the change. Doc drift is a bug.
- Inline comments on code that needs the *why* — sparingly. Code that doesn't need a comment shouldn't have one.

## When to involve the operator

- **Vision ambiguity:** an interpretation question the foundation docs cannot resolve. Pause; ask.
- **Everything else** is the engineer's call. The operator's job is the vision and the risk posture (now locked in the foundation docs); the engineer's job is the how. Conservative, recoverable, secure wins every tie.

## Definition of done for any stage

- Foundation docs re-read and the changes verified against them.
- Tests green; no expected-to-fail tests in the tree.
- `README.md`, `CHANGELOG.md`, and stage docs updated.
- Threat-model entry added or updated for any new attack surface.
- **Professionalization run as the closing step of a major phase** — the house `professionalize.md` protocol, including its §6 *Documentation Audit & Update* (docs-vs-reality drift, screenshot-gallery regen + verify-against-live-UI, links/CHANGELOG/architecture currency, proportional docs-only fixes, docs sign-off). The canonical Definition of Done lives in [`AGENTS.md`](AGENTS.md).
- Committed, pushed, tagged if a release boundary.
