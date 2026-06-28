"""Discord output status — the helper-computed state machine for the `/control/`
Discord card.

UNLIKE Mercury (whose checklist the *renderer* computes, because the renderer holds
the LiveKit env and writes a status file), the Discord checklist is computed by the
HELPER: the helper holds ``DISCORD_*`` and owns the public origin, and the renderer
is not involved at all (Discord reuses the HLS the renderer already produces). So
this lives helper-side and is merged into ``/api/outputs/status`` directly.

State machine (mirrors the Mercury discipline — honest, zero-egress-until-configured):
  disabled     — the output is off (no probe, no egress)
  needs_setup  — enabled but a prerequisite is missing (client id / secret / a
                 reachable public origin) → the card stays a launch-blocked shell
  ready        — client id + secret present AND the public origin is reachable.
                 "ready" means "an operator CAN launch the Activity", NOT that a
                 session is live — there is NO always-on "publishing" state, because
                 a bot cannot broadcast unattended (launch-to-start is a platform
                 rule). The card never claims a live Discord session.

The reachability check is REAL but NON-AUTHENTICATING: it GETs the Activity's own
``/api/discord/config`` through the operator's public origin (tunnel) — it speaks to
OUR origin only, never Discord; it opens no session, sends no token, holds no secret.
It is short-circuited until the cheap prerequisites pass AND skipped in phantom mode,
so a not-yet-configured Discord (and phantom) perform ZERO outbound traffic. It is
``None`` ("unknown") whenever it can't be checked safely.
"""

from __future__ import annotations

from collections.abc import Callable, Mapping
from urllib.parse import urlsplit

STATE_DISABLED = "disabled"
STATE_NEEDS_SETUP = "needs_setup"
STATE_READY = "ready"

OriginProbe = Callable[[str], bool | None]


def httpx_origin_probe(origin: str, *, timeout: float = 2.5) -> bool | None:
    """A real but NON-authenticating reachability check of the PUBLIC ORIGIN (not
    Discord): a short GET of our own ``/api/discord/config`` through the operator's
    tunnel. Returns True (2xx — the public surface answered through the tunnel),
    False (a transport/HTTP failure — genuinely down), or None (the origin is
    unusable → "unknown"). No Discord call, no token, no session, no secret."""
    parts = urlsplit(origin or "")
    if not parts.scheme or not parts.netloc:
        return None
    url = f"{parts.scheme}://{parts.netloc}/api/discord/config"
    try:
        import httpx

        resp = httpx.get(url, timeout=timeout)
        return 200 <= resp.status_code < 300
    except Exception:
        # A real attempt that failed = genuinely unreachable (down/misrouted), not
        # "unknown". (Unknown is reserved for "we never tried" — see callers.)
        return False


def make_cached_origin_probe(
    probe: OriginProbe | None = None, *, ttl_seconds: float = 30.0
) -> OriginProbe:
    """Wrap an origin probe with a short monotonic TTL cache so the `/control/`
    status poll (every few seconds) doesn't round-trip the tunnel on every tick.
    Pure-enough: state is confined to this closure. The default underlying probe is
    resolved HERE (not as a default arg) so tests can monkeypatch
    :func:`httpx_origin_probe` before the router is built."""
    import time

    underlying: OriginProbe = probe or httpx_origin_probe
    cache: dict[str, tuple[float, bool | None]] = {}

    def _cached(origin: str) -> bool | None:
        now = time.monotonic()
        hit = cache.get(origin)
        if hit is not None and (now - hit[0]) < ttl_seconds:
            return hit[1]
        value = underlying(origin)
        cache[origin] = (now, value)
        return value

    return _cached


def discord_status(
    *,
    output: Mapping | None,
    client_id: str | None,
    client_secret: str | None,
    public_origin: str | None,
    hls_enabled: bool,
    phantom: bool = False,
    probe: OriginProbe = httpx_origin_probe,
) -> dict:
    """Compute the Discord card's state + checklist. Pure given ``probe`` (injected
    for tests). ``output`` is ``outputs.discord`` from the wall config; ``hls_enabled``
    is ``outputs.hls.enabled`` (the Activity plays the HLS render, so it's surfaced
    as an honest prerequisite hint). NEVER posts to Discord; NEVER claims a live
    session."""
    o = output if isinstance(output, Mapping) else {}
    enabled = bool(o.get("enabled"))
    client_id_present = bool(client_id)
    client_secret_present = bool(client_secret)
    origin_set = bool(public_origin)

    if not enabled:
        return {
            "state": STATE_DISABLED,
            "transport": o.get("transport", "activity"),
            "guild_id": o.get("guild_id", ""),
            "checklist": {
                "client_id_present": client_id_present,
                "client_secret_present": client_secret_present,
                "public_origin_reachable": None,
                "hls_enabled": hls_enabled,
            },
            "detail": "Output disabled",
        }

    # Probe ONLY once the cheap prerequisites pass AND not in phantom mode → a
    # not-yet-configured Discord (and phantom) do ZERO egress; "unknown" otherwise.
    reachable: bool | None = None
    if client_id_present and client_secret_present and origin_set and not phantom:
        reachable = probe(public_origin or "")

    checklist = {
        "client_id_present": client_id_present,
        "client_secret_present": client_secret_present,
        "public_origin_reachable": reachable,
        "hls_enabled": hls_enabled,
    }

    # "ready" per the spec = creds present + origin reachable. (HLS-off doesn't block
    # readiness — it's an honest nudge in `detail` — because the operator may enable
    # HLS at launch time; but the card never pretends a session exists.)
    ready = client_id_present and client_secret_present and reachable is True
    if ready:
        detail = (
            "Ready — launch the Activity in a Discord voice channel to start"
            if hls_enabled
            else "Ready — also enable the HLS output (the Activity plays that render)"
        )
        return {
            "state": STATE_READY,
            "transport": o.get("transport", "activity"),
            "guild_id": o.get("guild_id", ""),
            "checklist": checklist,
            "detail": detail,
        }

    missing: list[str] = []
    if not client_id_present:
        missing.append("client id")
    if not client_secret_present:
        missing.append("client secret")
    if not origin_set:
        missing.append("public origin")
    elif reachable is False:
        missing.append("public origin unreachable")
    detail = "Waiting on setup: " + ", ".join(missing) if missing else "Waiting on setup"
    return {
        "state": STATE_NEEDS_SETUP,
        "transport": o.get("transport", "activity"),
        "guild_id": o.get("guild_id", ""),
        "checklist": checklist,
        "detail": detail,
    }
