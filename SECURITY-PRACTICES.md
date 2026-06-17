# Security Practices

> **Status:** Active — the operator's standing security baseline restated for this project, now carrying the project-specific controls that have landed (the helper's SSRF/egress shield, the desktop-executable localhost-bind / no-secrets posture, and TV-side APK release-signing). Threats and their mitigations are tracked stage-by-stage in `docs/THREAT-MODEL.md`.

The full rationale lives in `docs/foundation/02-TRUST-BAR.md`. This file restates the operational rules a contributor must follow at every commit.

---

## Standing rules (apply from the first commit)

### Secrets
- No secrets, tokens, credentials, API keys, or private addresses in git history. Ever.
- `.env.example` is in the repo. The real `.env` is gitignored.
- Never echo or print a secret to stdout. Write it directly to the consuming file. When a script logs, redact secrets before write.
- Pre-commit secret-scanning (gitleaks or equivalent) runs locally as an opt-in hook (`pre-commit install`). CI exists (`ci.yml` / `release.yml` / `screenshots.yml`), but a **gitleaks step on every push is not yet wired** (see `docs/adversarial-review-2026-06.md`, DEPLOY-4).
- Long-lived secrets have a named owner and a documented rotation path.

### Dependencies
- Lock files (`package-lock.json`, `uv.lock`, Gradle's verification metadata, etc.) are committed.
- Dependencies are pinned to exact versions. `latest` tags are forbidden in Docker images.
- Advisory scanning (`npm audit`, `pip-audit`, equivalent for Kotlin/Gradle) is **not yet wired** into CI (CI exists, but no `pip-audit`/`npm audit` step yet — see `docs/adversarial-review-2026-06.md`, DEPLOY-4). When wired: fix-available advisories block; no-fix advisories annotate and post to the operator's notification channel.

### Logs
- No secrets, no internal IPs, no user identifiers, no absolute filesystem paths beyond the project root in any log line.
- Structured JSON to stdout (helper). Captured by Docker `json-file` driver with rotation.

### Network
- Unrelated services sharing the helper's host are **never** touched; the helper stays isolated on its own network. Standing rule.
- The helper has bounded egress; it cannot reach anything on the operator's internal network beyond what its two jobs require.
- Inbound: the TV app talks only to the helper; the helper does not expose itself to the public internet.

### Containers (helper side)
- Non-root user.
- Pinned image versions by digest where possible.
- `cap_drop: [ALL]`, `no-new-privileges`, `read_only: true` where the workload allows.
- Secrets injected at runtime via env or file mount; never baked into the image.
- No `docker.sock` mount.

### App (TV side)
- No credentials shipped in the APK.
- Requests only the OS permissions it actually needs.
- Release signing is implemented — a v1+v2+v3 `signingConfig` sourced from a **gitignored** `app/keystore.properties` (modeled on `app/keystore.properties.example`), an `IS_RELEASE_SIGNED` BuildConfig flag, and a deploy guard that refuses to push a debug-signed APK. *(The operator supplies the keystore. `versionCode` is now version-derived and the helper URL is runtime-configurable — so a stock APK no longer needs a rebuild; publishing a signed release build for end users is what remains on the open distribution list.)*

### Desktop executable (packaged app — `tools/desktop/`)
- **Localhost-only bind.** The packaged app serves the helper on `127.0.0.1` (a
  browser secure context — no TLS needed), never `0.0.0.0`. It is not exposed to
  the LAN or the internet; only the same machine can reach it. (The container
  entrypoint binds `0.0.0.0` behind Docker NAT; the desktop launcher does not.)
- **No secrets in the bundle.** The bundle carries only code, the web client, and
  the public channel/feed seeds. There are no keys, tokens, or credentials baked
  in (the app is credential-free, like the rest of MyMTS). Code-signing secrets
  live in env/CI secrets, never in the repo or the bundle, and are never printed.
- **Writable state outside the bundle.** The (read-only) bundle is never written
  to; the seeded DB + any cached state live in the per-user data dir.
- **yt-dlp self-update network use.** The only outbound traffic the launcher
  itself makes is the yt-dlp self-update: it fetches ONLY from PyPI's official
  hosts (`pypi.org`, `files.pythonhosted.org`) over **HTTPS** (host-allowlisted),
  verifies the wheel's **SHA256 against PyPI's published digest** before use, and
  loads only a pure-python wheel of the same trusted dependency — no arbitrary
  URL, no native binary. Every failure falls back to the bundled yt-dlp. Trust
  root: PyPI + TLS. (The helper's own upstream egress is unchanged + still
  SSRF-guarded.)
- **Signing posture.** Builds are signed + notarized when credentials are present
  (gated) and honestly unsigned otherwise, with a documented right-click→Open
  first-run path; unsigned releases are marked pre-release.

### Validation
- Everything the helper accepts from outside is validated by structured parsing, not loose regex.
- Network input is treated as hostile until proven safe. The burden is on the input.
- Reject anything that resolves toward loopback, private, or link-local space when fetching upstream.

### Boundary
- The boundary between TV and helper, and between the helper and the rest of the NAS, is exercised by tests. A protection that has no test does not exist.
- A protection that quietly stops working is itself an alertable condition.

---

## Where to find things

- **Why** each rule exists: `docs/foundation/02-TRUST-BAR.md` (each principle has a "Traces to" footer).
- **Specific threats and mitigations** for the project: `docs/THREAT-MODEL.md` (populated stage-by-stage).
- **Operational procedures** (rotation, backup, restore, recovery): `docs/OPERATIONS.md`.

---

## When you find a security issue

Open it as a private issue or DM the operator. Do not post details publicly. If it's about a dependency, file an advisory waiver with an expiry in the threat model rather than a public CVE ticket.
