# Security Policy

MyMTS is a self-hosted, LAN-scoped project built around one non-negotiable principle:
**it can never become a path into the home network.** Security reports are welcome.

## Reporting a vulnerability

Please report privately — **do not** open a public issue for a suspected vulnerability:

- **Preferred:** use GitHub's **[Private Vulnerability Reporting](https://github.com/wbuscombe/mymts/security/advisories/new)**
  (the "Report a vulnerability" button on the repository's **Security** tab). It opens a
  private advisory thread visible only to the maintainer.

If you can, include: affected component (helper / renderer / native app / web client), a
description, and the steps to reproduce.

## Scope & supported versions

Only the latest `main` (and the most recent release tag) is supported. This is a solo-maintained
homelab/hobby project, so responses are best-effort — but the trust posture below is taken seriously.

## Where the security model lives

- **[`SECURITY-PRACTICES.md`](SECURITY-PRACTICES.md)** — the concrete practices (secret handling,
  SSRF defenses, pinned deps, pre-commit secret scanning, the LAN-only trust boundary).
- **[`docs/THREAT-MODEL.md`](docs/THREAT-MODEL.md)** — the threat model and the assets it protects.
