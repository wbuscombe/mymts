# Contributing

> **Status (Stage 0):** Skeleton — the operator's standing standards captured here so they apply from the first commit. Stage-specific guidance (build commands, test commands) lands when the toolchain does.

## Read this first

Before you touch anything, read the foundation docs:

1. `docs/foundation/01-VISION.md`
2. `docs/foundation/02-TRUST-BAR.md`
3. `docs/foundation/03-OPERATIONAL-BAR.md`
4. `docs/foundation/04-TECHNICAL-APPROACH.md`
5. `docs/foundation/00-READING.md` — anti-drift restatement + precedence order.

Then the engineering brief: `docs/BUILD-PROMPT.md`.

When in doubt, the foundation docs win.

## Commits

- **Conventional commits.** Types: `feat`, `fix`, `docs`, `chore`, `test`, `security`, `refactor`, `perf`, `build`, `ci`.
- One logical change per commit. The body explains *why*, not what (the diff explains what).
- `BREAKING CHANGE:` footer required for any change that breaks the operator's stored data or the helper API contract.

## Branching + releases

- `main` is the long-lived branch. Stage milestones land in `main`.
- Per-stage work happens in branches named `stage-N/<short-slug>`. Each ends green (tests passing) before merge.
- Releases are **semver**, **tagged**, and **only created when a stage gate is met**. v0.1.0 lands when Stage 1's GATE clears.

## Tests

- TDD as the default. Critical paths and the trust-boundary behaviors **must** have tests.
- Single-command test runner documented in README at each stage. No flaky or expected-to-fail tests in the suite.
- Tests run green before any commit and before any release.

## Code style

- `.editorconfig` is authoritative.
- Kotlin: 4-space indent, max line 120; idiomatic Compose; no `!!` on platform types from Java interop.
- Python (helper if Python-based): 4-space indent, max line 100; type hints; `ruff` + `mypy` strict.
- Comments explain **why** something is the way it is, not what it does. The diff and the names explain what.

## Security practices (every contribution honors these)

- **Never** commit secrets, tokens, credentials, API keys, or absolute paths beyond the project root.
- `.env.example` template lives in the repo; the real `.env` does not.
- Never echo or print a secret to stdout — write it directly to the file or pass by env var reference.
- Never touch the unrelated host container on the NAS for any reason.
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
- Committed, pushed, tagged if a release boundary.
