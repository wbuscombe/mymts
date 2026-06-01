"""Environment-driven config.

Everything tunable comes from env vars. No file-based config in v1 — keeps
the surface small and keeps secret-handling honest (env is read-only at
startup, never logged).
"""

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Config:
    phantom_mode: bool
    port: int
    log_level: str
    build_sha: str
    build_version: str
    # Fields with defaults — tests can construct a minimal Config without
    # naming each operational knob, and `from_env` still overrides from
    # the environment.
    data_dir: str = "/data"
    feed_poll_interval_seconds: int = 300
    feed_retention_days: int = 14
    channel_probe_interval_seconds: int = 30 * 60

    @classmethod
    def from_env(cls) -> Config:
        return cls(
            phantom_mode=os.environ.get("PHANTOM_MODE", "0") == "1",
            port=int(os.environ.get("PORT", "8091")),
            log_level=os.environ.get("LOG_LEVEL", "info").lower(),
            build_sha=os.environ.get("BUILD_SHA", "dev"),
            build_version=os.environ.get("BUILD_VERSION", "0.0.0-dev"),
            # /data is the conventional bind-mount point for stateful
            # containers in the operator's NAS layout. Falls back to a
            # local path for `uv run python -m mymts_helper`.
            data_dir=os.environ.get("DATA_DIR", "/data"),
            feed_poll_interval_seconds=int(
                os.environ.get("FEED_POLL_INTERVAL_SECONDS", "300")
            ),
            feed_retention_days=int(os.environ.get("FEED_RETENTION_DAYS", "14")),
            channel_probe_interval_seconds=int(
                os.environ.get("CHANNEL_PROBE_INTERVAL_SECONDS", str(30 * 60))
            ),
        )
