# OPERATIONS

> **Status (Stage 0):** Skeleton. The operational outcomes are locked in `docs/foundation/03-OPERATIONAL-BAR.md`; the mechanisms that achieve them are filled in Stage 1 (spike), Stage 6 (hardening), and Stage 7 (portability + final docs).

---

## Sections to populate (and which stage owns each)

| Section | Owned by | What lives here |
|---|---|---|
| Install (cold-start, fresh box → on-the-wall) | Stage 1 + Stage 6 | The documented, repeatable path. Reproducible. Single source of truth. |
| Update + rollback | Stage 6 | Signed-install update path. Always a way back. No silent fleet cascade. How to abort a rollout. |
| Backup + restore | Stage 6 | On-device data backup + restore procedure. Stored where, encrypted how, restored how. |
| Self-tending upkeep | Stage 6 | Cleanup, rotation, renewals, retries. Cron, scheduled tasks, etc. |
| Operator alerting | Stage 6 | The channel the operator actually sees (existing `claude-status-bot` integration pattern). What fires alerts and when. |
| Health-at-a-glance | Stage 3 + Stage 5 + Stage 6 | In-app diagnostic view + helper health endpoint contract. |
| Recovery procedures | Stage 6 + Stage 7 | "Helper is broken, what now." "App won't start, what now." "Backup restore." |
| NAS layout + Docker conventions | Stage 2 | Helper's location on the NAS, log paths, secret paths, follows operator's existing standard layout. |

---

## Standing operational rules (apply from the first commit)

- **unrelated host container is never touched.** Restart, modify, reconfigure — all forbidden unless the operator explicitly requests it.
- **Auto-deploy is `pull → rebuild → restart`.** A bare restart never picks up changes. The deploy script verifies the full cycle.
- **No secrets in any log line, error message, or alert payload.**
- **The operator is alerted through a channel they actually see** (Op Bar C2). The default is the existing notification bot pattern (`claude-status-bot`).
- **Health is legible without deep investigation** (Op Bar C3). `/health` returns truth; the in-app diagnostics surface it.

---

## Health endpoint contract (Stage 2 will pin this)

The helper exposes a health endpoint that:

- Returns a small JSON document with `schema_version` so consumers can detect drift.
- States freshness for each upstream (last successful fetch + per-source error count).
- States whether protections (egress allowlist, etc.) are in force.
- Includes build SHA + version + uptime.

A contract test in the operator's monitoring tooling and in MyMTS itself prevents silent schema drift. Schema details land in Stage 2.

---

## Install path placeholder (Stage 1 will write this)

```
# placeholder — filled in Stage 1 once the toolchain is set up
```

## Backup + restore placeholder (Stage 6 will write this)

```
# placeholder — backup goes to <NAS bind-mount>, restore is documented end-to-end
```
