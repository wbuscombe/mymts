"""Mercury output — the publisher abstraction + the inert STUB.

The renderer's output manager routes the ``mercury`` output to a ``MercuryPublisher``
instead of an ffmpeg→HLS encoder. This build ships ONLY :class:`StubMercuryPublisher`,
which NEVER opens a LiveKit session, mints a token, or publishes media — it reports a
"needs setup" status so ``/control/`` can render the Mercury card as a pre-fillable
shell, and logs its (inert) intent on start/stop/restart.

The real wire-up is a separate, later step that drops into this seam:

  # MERCURY-WIRE-UP: replace StubMercuryPublisher with the real LiveKit
  # screen_share publisher (mint JWT off LIVEKIT_API_KEY/SECRET, connect to
  # LIVEKIT_HOST, publish a source=screen_share H.264 track from the Mercury
  # encode branch + optional screen_share_audio, dedicated identity, self-reconnect).

See docs/mercury-wireup-notes.md for the full spec.
"""

from __future__ import annotations

import socket
from abc import ABC, abstractmethod
from collections.abc import Callable, Mapping
from urllib.parse import urlparse

# The Mercury status state machine surfaced to /control/ via the status file:
#   disabled         — the output is off (no checklist work, no probe, no egress)
#   needs_setup      — enabled but the checklist isn't satisfied (missing key /
#                      channel / tailnet) → the card stays a shell, actions disabled
#   ready_not_wired  — every prerequisite present, but the publisher is the STUB:
#                      the path is "armed" yet deliberately not implemented here
STATE_DISABLED = "disabled"
STATE_NEEDS_SETUP = "needs_setup"
STATE_READY_NOT_WIRED = "ready_not_wired"

# env var NAMES (values live ONLY in the gitignored .env — never logged as secrets)
ENV_API_KEY = "LIVEKIT_API_KEY"
ENV_API_SECRET = "LIVEKIT_API_SECRET"
ENV_HOST = "LIVEKIT_HOST"
ENV_BOT_IDENTITY = "LIVEKIT_BOT_IDENTITY"


class MercuryPublisher(ABC):
    """The seam the future real LiveKit publisher drops into. The output manager
    calls ``configure`` with the live ``outputs.mercury`` config, then ``start`` /
    ``stop`` / ``restart``, and reads ``status`` for the status file."""

    @abstractmethod
    def configure(self, mercury_output: Mapping) -> None: ...

    @abstractmethod
    def start(self) -> None: ...

    @abstractmethod
    def stop(self) -> None: ...

    @abstractmethod
    def restart(self) -> None: ...

    @abstractmethod
    def status(self) -> dict: ...


def _tcp_probe(host_url: str, timeout: float = 2.0) -> bool | None:
    """A real but NON-LiveKit reachability probe: a short TCP connect to the
    signaling host:port, immediately closed. It speaks NO protocol — no TLS
    handshake, no WebSocket, no LiveKit, no token, no media. Returns True
    (reachable), False (refused/unreachable), or None (couldn't parse a host →
    "unknown", never fabricated). Only ever called once the key + channel are
    already present (so a not-yet-configured Mercury performs ZERO egress)."""
    try:
        parsed = urlparse(host_url)
        host = parsed.hostname
        if not host:
            return None
        port = parsed.port or (443 if parsed.scheme in ("wss", "https") else 80)
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False
    except Exception:
        return None


class StubMercuryPublisher(MercuryPublisher):
    """The ONLY Mercury impl in this build. Inert by construction: it opens no
    socket on start/stop/restart and mints no token. It computes an honest
    setup-checklist for the status file and short-circuits the tailnet probe until
    the key + channel are present (so the default — no creds — does NO network)."""

    def __init__(
        self,
        *,
        env: Mapping | None = None,
        log: Callable[[str], None] = print,
        probe: Callable[[str], bool | None] = _tcp_probe,
    ) -> None:
        import os
        self._env = env if env is not None else os.environ
        self._log = log
        self._probe = probe
        self._cfg: dict = {"enabled": False}

    def configure(self, mercury_output: Mapping) -> None:
        self._cfg = dict(mercury_output or {})

    # start/stop/restart are INERT: they log intent + never touch the network.
    def start(self) -> None:
        c = self._cfg
        identity = self._env.get(ENV_BOT_IDENTITY) or "mymts-wall-bot"
        self._log(
            f"mercury stub: would publish wall → channel-{c.get('channel_guid') or '?'} "
            f"at {c.get('resolution', '?')} as identity {identity} — NOT wired "
            f"(no socket, no JWT, no media)"
        )

    def stop(self) -> None:
        self._log("mercury stub: would stop publishing — NOT wired (no-op)")

    def restart(self) -> None:
        self._log("mercury stub: would restart the publisher — NOT wired (no-op)")

    def status(self) -> dict:
        c = self._cfg
        if not c.get("enabled"):
            return {
                "state": STATE_DISABLED,
                "checklist": {"key_present": False, "channel_set": False, "tailnet_reachable": None},
                "detail": "Output disabled",
            }
        key_present = bool(self._env.get(ENV_API_KEY) and self._env.get(ENV_API_SECRET))
        channel_set = bool(c.get("channel_guid"))
        # Probe ONLY once the cheap checks pass — so a Mercury without creds never
        # opens a socket (zero egress in this build).
        tailnet: bool | None = None
        if key_present and channel_set:
            tailnet = self._probe(self._env.get(ENV_HOST, ""))
        checklist = {
            "key_present": key_present,
            "channel_set": channel_set,
            "tailnet_reachable": tailnet,
        }
        if key_present and channel_set and tailnet is True:
            return {
                "state": STATE_READY_NOT_WIRED,
                "checklist": checklist,
                "detail": "Armed — publisher not yet wired (stub)",
            }
        missing = []
        if not key_present:
            missing.append("LiveKit key")
        if not channel_set:
            missing.append("channel")
        if key_present and channel_set and tailnet is not True:
            missing.append("tailnet")
        detail = "Waiting on setup: missing " + ", ".join(missing) if missing else "Waiting on setup"
        return {"state": STATE_NEEDS_SETUP, "checklist": checklist, "detail": detail}
