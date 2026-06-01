"""/health endpoint.

`schema_version` stays pinned at 1: Stage 2 adds the `feeds` and `channels`
sub-objects but does not remove or rename anything that Stage 1's
consumers (the contract test, claude-status-bot) relied on. Adding fields
to a JSON object response is a backward-compatible change; a bump would
only be needed on removal/rename.

The shape is consumed by claude-status-bot, so we deliberately keep it
narrow and stable.
"""

from __future__ import annotations

import sqlite3
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .config import Config

SCHEMA_VERSION = 1

# Threshold: a source is "stale" if it hasn't had a success in this many
# minutes. The default polling cadence is every 5 minutes, so 15 covers a
# couple of missed cycles before alerting fires.
STALE_SOURCE_THRESHOLD_MINUTES = 15


@dataclass(frozen=True)
class FreshnessSnapshotter:
    """Read-only inspector that builds the `feeds` and `channels`
    sub-objects from the current DB state. Held as a dataclass so it's
    easy to inject a stub in tests."""

    db_path: Path

    def feeds(self) -> dict[str, Any]:
        conn = sqlite3.connect(self.db_path)
        try:
            conn.row_factory = sqlite3.Row
            srow = conn.execute(
                "SELECT COUNT(*) AS n FROM sources WHERE enabled=1"
            ).fetchone()
            irow = conn.execute(
                "SELECT COUNT(*) AS n FROM feed_items"
            ).fetchone()
            stale = [
                r["label"]
                for r in conn.execute(
                    "SELECT label FROM sources WHERE enabled=1 AND "
                    "(last_success_at IS NULL OR "
                    " last_success_at < datetime('now', ?))",
                    (f"-{STALE_SOURCE_THRESHOLD_MINUTES} minutes",),
                )
            ]
            return {
                "sources_count": int(srow["n"]),
                "items_count": int(irow["n"]),
                "stale_sources": stale,
            }
        finally:
            conn.close()

    def channels(self) -> dict[str, Any]:
        conn = sqlite3.connect(self.db_path)
        try:
            conn.row_factory = sqlite3.Row
            crow = conn.execute(
                "SELECT COUNT(*) AS n FROM channels WHERE enabled=1"
            ).fetchone()
            lrow = conn.execute(
                "SELECT COUNT(*) AS n FROM channels WHERE enabled=1 AND status='live'"
            ).fetchone()
            urow = conn.execute(
                "SELECT COUNT(*) AS n FROM channels WHERE enabled=1 AND status='unavailable'"
            ).fetchone()
            return {
                "channels_count": int(crow["n"]),
                "live_count": int(lrow["n"]),
                "unavailable_count": int(urow["n"]),
            }
        finally:
            conn.close()


@dataclass(frozen=True)
class HealthState:
    started_at: float
    snapshotter: FreshnessSnapshotter | None = None

    def snapshot(
        self,
        config: Config,
        *,
        last_poll_at: str | None = None,
        last_probe_at: str | None = None,
    ) -> dict[str, object]:
        out: dict[str, object] = {
            "schema_version": SCHEMA_VERSION,
            "ok": True,
            "ready": True,
            "phantom": config.phantom_mode,
            "build_sha": config.build_sha,
            "version": config.build_version,
            "uptime_seconds": round(time.monotonic() - self.started_at, 3),
        }
        if self.snapshotter is not None:
            feeds = self.snapshotter.feeds()
            feeds["last_poll_at"] = last_poll_at
            channels = self.snapshotter.channels()
            channels["last_probe_at"] = last_probe_at
            out["feeds"] = feeds
            out["channels"] = channels
        return out
