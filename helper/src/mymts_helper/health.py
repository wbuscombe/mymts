"""/health endpoint.

Pinned schema with `schema_version` so claude-status-bot and any future
consumer can detect drift. Schema changes require a major bump and a
contract update on the consumer side.

Stage 1 fields are intentionally narrow — only what's already true about
the helper. Stage 2 adds per-source RSS freshness; Stage 6 adds upkeep
results, backup status, and protections-in-force flags.
"""

from __future__ import annotations

import time
from dataclasses import dataclass

from .config import Config

SCHEMA_VERSION = 1


@dataclass(frozen=True)
class HealthState:
    started_at: float

    def snapshot(self, config: Config) -> dict[str, object]:
        return {
            "schema_version": SCHEMA_VERSION,
            "ok": True,
            "ready": True,
            "phantom": config.phantom_mode,
            "build_sha": config.build_sha,
            "version": config.build_version,
            "uptime_seconds": round(time.monotonic() - self.started_at, 3),
        }
