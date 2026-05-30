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

    @classmethod
    def from_env(cls) -> Config:
        return cls(
            phantom_mode=os.environ.get("PHANTOM_MODE", "0") == "1",
            port=int(os.environ.get("PORT", "8091")),
            log_level=os.environ.get("LOG_LEVEL", "info").lower(),
            # BUILD_SHA / BUILD_VERSION are injected at container build time.
            # Defaults make local-dev legible without lying about being a release build.
            build_sha=os.environ.get("BUILD_SHA", "dev"),
            build_version=os.environ.get("BUILD_VERSION", "0.0.0-dev"),
        )
