# Operations

Operational runbooks for MyMTS — deployment, device provisioning, TLS-cert
rotation, backup/restore, and recovery — are **environment-specific**: they
reference a particular host, network, device layout, and file paths. To keep
this repository clean and portable, they are **not committed**. Maintainers keep
their own deployment runbook in operator-local notes under `docs/ops-local/`
(gitignored).

**If you are setting MyMTS up yourself, you don't need them.** See:

- [`../ONBOARDING.md`](../ONBOARDING.md) — clone-to-running (demo mode + a local
  helper on real public data), no secrets and no NAS required.
- [`foundation/03-OPERATIONAL-BAR.md`](foundation/03-OPERATIONAL-BAR.md) — the
  operational outcomes the project targets (update/rollback, backup, health).
- [`../SECURITY-PRACTICES.md`](../SECURITY-PRACTICES.md) — the security/ops
  posture for the helper (non-root, read-only, least-authority, pinned images).

> **Maintainers:** keep host names, IPs, paths, MACs, and device specifics in
> `docs/ops-local/` only — never in committed docs. This is the *audience-aware
> docs* standard: committed docs are for anyone who clones the repo; deployment
> specifics belong in local notes.
