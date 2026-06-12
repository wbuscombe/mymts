# Security Practices

> **Status (Stage 0):** Skeleton — the operator's standing security baseline restated for this project. Project-specific controls land per stage and are tracked in `docs/THREAT-MODEL.md`.

The full rationale lives in `docs/foundation/02-TRUST-BAR.md`. This file restates the operational rules a contributor must follow at every commit.

---

## Standing rules (apply from the first commit)

### Secrets
- No secrets, tokens, credentials, API keys, or private addresses in git history. Ever.
- `.env.example` is in the repo. The real `.env` is gitignored.
- Never echo or print a secret to stdout. Write it directly to the consuming file. When a script logs, redact secrets before write.
- Pre-commit secret-scanning (gitleaks or equivalent) runs locally as an opt-in hook (`pre-commit install`). A CI gate to re-run it on every push is **planned** — no CI exists yet (see `docs/adversarial-review-2026-06.md`, DEPLOY-4).
- Long-lived secrets have a named owner and a documented rotation path.

### Dependencies
- Lock files (`package-lock.json`, `uv.lock`, Gradle's verification metadata, etc.) are committed.
- Dependencies are pinned to exact versions. `latest` tags are forbidden in Docker images.
- Advisory scanning (`npm audit`, `pip-audit`, equivalent for Kotlin/Gradle) is **planned** to run in CI (no CI exists yet — see `docs/adversarial-review-2026-06.md`, DEPLOY-4). When wired: fix-available advisories block; no-fix advisories annotate and post to the operator's notification channel.

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
- Signed installs with a documented keystore-management story (lands in Stage 6).

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
