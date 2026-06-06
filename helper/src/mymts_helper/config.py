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
    # Ticker (markets + sports) — added 2026-06-05. Polite intervals on
    # keyless public sources (Stooq, CoinGecko, ESPN scoreboard): markets
    # move minute-to-minute; sports scores update on a slower cadence.
    markets_poll_interval_seconds: int = 120
    sports_poll_interval_seconds: int = 180
    # Stage 6 / TLS track — added 2026-06-03.
    #
    # `https_port` enables HTTPS on a second listener when paired with a
    # cert + key path. When unset, the helper serves HTTP only (the v1
    # behaviour). When both HTTP and HTTPS ports are configured, the
    # helper serves both **from the same app instance** — pollers run
    # once via the app lifespan, exposed identically on both endpoints.
    # This is the operator-away-safe transition: the existing HTTP
    # endpoint keeps working while the TV moves to HTTPS, and the HTTP
    # endpoint can be removed in a follow-up commit once the move is
    # confirmed.
    https_port: int | None = None
    ssl_keyfile: str | None = None
    ssl_certfile: str | None = None
    # LAN web client (2026-06-06). When set to a directory path, the
    # helper mounts that directory's static files at `/app` (same-origin
    # as the API, so no CORS). OFF by default — the running helper is
    # unchanged unless the operator opts in via WEB_CLIENT_DIR. The
    # served content is the credential-free LAN web client (see
    # `web/README.md`); it is never exposed beyond the LAN.
    web_client_dir: str | None = None

    @classmethod
    def from_env(cls) -> Config:
        def _opt_int(name: str) -> int | None:
            v = os.environ.get(name)
            return int(v) if v and v.strip() else None

        def _opt_str(name: str) -> str | None:
            v = os.environ.get(name)
            return v if v else None

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
            markets_poll_interval_seconds=int(
                os.environ.get("MARKETS_POLL_INTERVAL_SECONDS", "120")
            ),
            sports_poll_interval_seconds=int(
                os.environ.get("SPORTS_POLL_INTERVAL_SECONDS", "180")
            ),
            https_port=_opt_int("HTTPS_PORT"),
            ssl_keyfile=_opt_str("SSL_KEYFILE"),
            ssl_certfile=_opt_str("SSL_CERTFILE"),
            web_client_dir=_opt_str("WEB_CLIENT_DIR"),
        )

    def has_https(self) -> bool:
        """True iff HTTPS is fully configured (port + key + cert).

        All three must be set; otherwise the helper serves HTTP only.
        This keeps misconfiguration honest — a half-configured TLS
        setup never silently degrades to "almost encrypted."
        """
        return bool(self.https_port and self.ssl_keyfile and self.ssl_certfile)
