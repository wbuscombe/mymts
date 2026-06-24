"""Environment-driven config.

Everything tunable comes from env vars. No file-based config in v1 — keeps
the surface small and keeps secret-handling honest (env is read-only at
startup, never logged).
"""

from __future__ import annotations

import os
from dataclasses import dataclass


def _default_data_dir() -> str:
    """DB location when DATA_DIR is unset. Containers mount a writable /data
    volume; a local run (clean clone / demo) has no writable /data, so fall back
    to a writable per-user dir so the helper boots with zero config."""
    if os.path.isdir("/data") and os.access("/data", os.W_OK):
        return "/data"
    base = os.environ.get("XDG_DATA_HOME") or os.path.expanduser("~/.local/share")
    return os.path.join(base, "mymts-helper")


def _default_web_client_dir() -> str | None:
    """Locate the in-repo web/ client so a clean clone / demo serves it at /app
    with no config (matches .phantom.yml + ONBOARDING). Used only in phantom
    mode; production serving stays opt-in via WEB_CLIENT_DIR. Returns None if not
    found (e.g. an installed package outside the source tree)."""
    here = os.path.dirname(os.path.abspath(__file__))
    repo_root = os.path.abspath(os.path.join(here, "..", "..", ".."))
    for c in (
        os.path.join(repo_root, "web"),
        os.path.join(os.getcwd(), "web"),
        os.path.join(os.getcwd(), "..", "web"),
    ):
        if os.path.isfile(os.path.join(c, "index.html")):
            return c
    return None


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
    # yt-dlp YouTube-live resolver (2026-06-16). Per-extraction socket timeout
    # for kind='youtube' channels. 25s default matches the resolver's
    # DEFAULT_RESOLVE_TIMEOUT; the prober adds an async deadline on top.
    youtube_resolve_timeout_seconds: int = 25
    # Ticker (markets + sports) — added 2026-06-05. Polite intervals on
    # keyless public sources (Yahoo Finance, CoinGecko, ESPN scoreboard):
    # markets move minute-to-minute; sports scores update on a slower cadence.
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
    # Playlist profiles (2026-06-13). Optional path to a JSON file of named
    # channel profiles for the /api/playlist/{name}.m3u endpoint (see
    # `profiles.example.json`). Unset → only the built-in `default` profile
    # (every live channel). Operator data, kept out of git — like the cert
    # paths above, only the path comes from the environment.
    profiles_file: str | None = None
    # Renderer HLS stream (headless-container version, 2026-06-24). When set to a
    # directory, the helper serves the HLS playlist + segments the renderer
    # container writes there at /api/stream (one serving surface for VLC / Apple
    # TV). The directory is a SHARED volume the renderer mounts rw and the helper
    # mounts read-only. OFF by default — unset → no /api/stream route, the helper
    # is byte-identical to its prior self.
    stream_dir: str | None = None

    @classmethod
    def from_env(cls) -> Config:
        def _opt_int(name: str) -> int | None:
            v = os.environ.get(name)
            return int(v) if v and v.strip() else None

        def _opt_str(name: str) -> str | None:
            v = os.environ.get(name)
            return v if v else None

        phantom = os.environ.get("PHANTOM_MODE", "0") == "1"
        # Web client: opt-in via WEB_CLIENT_DIR. In phantom/demo mode, default to
        # the in-repo web/ so a fresh clone serves /app with no config (matches
        # .phantom.yml + ONBOARDING). Production stays opt-in / byte-identical.
        web_dir = _opt_str("WEB_CLIENT_DIR")
        if web_dir is None and phantom:
            web_dir = _default_web_client_dir()

        return cls(
            phantom_mode=phantom,
            port=int(os.environ.get("PORT", "8091")),
            log_level=os.environ.get("LOG_LEVEL", "info").lower(),
            build_sha=os.environ.get("BUILD_SHA", "dev"),
            build_version=os.environ.get("BUILD_VERSION", "0.0.0-dev"),
            # Where the sqlite db lives. A container mounts a writable /data
            # volume; a local `uv run` (a clean clone / demo) has no writable
            # /data, so we auto-fall-back to a per-user dir — no config needed
            # to boot from a fresh clone. DATA_DIR overrides either way.
            data_dir=os.environ.get("DATA_DIR") or _default_data_dir(),
            feed_poll_interval_seconds=int(
                os.environ.get("FEED_POLL_INTERVAL_SECONDS", "300")
            ),
            feed_retention_days=int(os.environ.get("FEED_RETENTION_DAYS", "14")),
            channel_probe_interval_seconds=int(
                os.environ.get("CHANNEL_PROBE_INTERVAL_SECONDS", str(30 * 60))
            ),
            youtube_resolve_timeout_seconds=int(
                os.environ.get("YOUTUBE_RESOLVE_TIMEOUT_SECONDS", "25")
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
            web_client_dir=web_dir,
            profiles_file=_opt_str("PROFILES_FILE"),
            stream_dir=_opt_str("STREAM_DIR"),
        )

    def has_https(self) -> bool:
        """True iff HTTPS is fully configured (port + key + cert).

        All three must be set; otherwise the helper serves HTTP only.
        This keeps misconfiguration honest — a half-configured TLS
        setup never silently degrades to "almost encrypted."
        """
        return bool(self.https_port and self.ssl_keyfile and self.ssl_certfile)
