"""Mercury output — the real LiveKit `screen_share` publisher (+ the abstraction).

The renderer's output manager routes the ``mercury`` output to a ``MercuryPublisher``
instead of an ffmpeg→HLS encoder. This build ships the REAL publisher:

  MERCURY-WIRE-UP: the real LiveKit screen_share publisher (browser SDK on the
  shared Xvfb) — replaces the former StubMercuryPublisher.

**Mechanism (browser SDK + the single HLS render — render-once preserved).** A
dedicated publisher Chrome runs inside the renderer container (on the shared Xvfb
``DISPLAY``). It loads a tiny local ``publisher.html`` that runs ``livekit-client``,
plays the wall's existing HLS (the single composite the wall Chrome → ffmpeg already
produces) in a hidden ``<video>`` via hls.js, and publishes ``video.captureStream()``
as a SIMULCAST ``source: screen_share`` track. This RE-USES the one render (no second
compositing) and even carries the wall's mixed audio. The browser SDK is mandatory:
Mercury's hand-rolled ``/livekit-proxy`` is only proven against ``livekit-client``
(note M) — a Go/Rust server SDK would traverse unproven paths.

(``getDisplayMedia`` framebuffer capture is the lower-latency ideal but is
non-functional under the renderer's headless Xvfb — Chromium's X11 desktop capturer
fails ``SelectSource`` with no XRandR monitor, across every flag. captureStream of the
HLS is the robust render-once equivalent; see ``docs/decisions/0003`` for the
investigation. The publisher Chrome's window is still parked OFF the WxH framebuffer
so it never pollutes the HLS ``x11grab``.) The token is minted server-side here — the
API secret NEVER reaches the browser; only the short-lived JWT does, via the localhost
control server.

The :class:`StubMercuryPublisher` is retained as the zero-egress fallback when no
credentials are present (the factory below picks it), and for unit tests.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import socket
import subprocess
import threading
import time
from abc import ABC, abstractmethod
from collections.abc import Callable, Mapping
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

import supervisor

# ---- the status state machine surfaced to /control/ via the status file ----
STATE_DISABLED = "disabled"            # output off — no probe, no Chrome, no egress
STATE_NEEDS_SETUP = "needs_setup"      # enabled but key/secret/channel missing
STATE_CONNECTING = "connecting"        # publisher Chrome launched, joining the room
STATE_CONNECTED = "connected"          # joined, not yet publishing the track
STATE_PUBLISHING = "publishing"        # screen_share track up + the SFU is pulling it
STATE_DYNACAST_PAUSED = "dynacast_paused"  # connected + published but idle (no viewers) — NORMAL
STATE_RECONNECTING = "reconnecting"    # transport blip / re-mint reload, backing off
STATE_ERROR = "error"                  # the publisher process died / a fatal init error

# env var NAMES (values live ONLY in the gitignored .env — never logged as secrets)
ENV_API_KEY = "LIVEKIT_API_KEY"
ENV_API_SECRET = "LIVEKIT_API_SECRET"
ENV_HOST = "LIVEKIT_HOST"               # the wss://…/livekit-proxy signaling URL
ENV_BOT_IDENTITY = "LIVEKIT_BOT_IDENTITY"
ENV_NODE_IP = "LIVEKIT_NODE_IP"         # the tailnet media IP (DNS map target — note H)
ENV_HLS_URL = "RENDER_HLS_URL"          # the wall's HLS the publisher captures (render-once)

# The wall's HLS, served by the helper on the LAN (plain HTTP — no cert dance). The
# publisher plays this single render + captureStreams it (render-once).
DEFAULT_HLS_URL = "http://mymts-helper:8082/api/stream/playlist.m3u8"
DEFAULT_IDENTITY = "mymts-wall-bot"
DEFAULT_DISPLAY_NAME = "MyMTS News Wall"
# Mercury's production SFU hostname — the cert SNI we connect under (note H). Chrome
# resolves it to LIVEKIT_NODE_IP via --host-resolver-rules; we never dial the IP.
PROD_HOST = "chat.mercurychat.net"
# Screen-share simulcast tops out at 1080p (no point pushing a 4K wall down a chat
# pipe). The ladder is ascending, so the cap is an index comparison.
MERCURY_MAX_RESOLUTION = "1080p"
TOKEN_TTL_S = 24 * 3600                 # note 18: 24h, re-minted on each (re)start/reload

PUBLISHER_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "publisher")


# ============================ pure helpers (no Chrome) ============================

def _b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def video_grant(room: str) -> dict:
    """The EXACT publish-only LiveKit grant (Mercury notes E, K, L):
      roomJoin + the room + canPublish; canSubscribe=False (a default grant would
      pull every participant's mic to the NAS — note E); canPublishData=False."""
    return {
        "room": room,
        "roomJoin": True,
        "canPublish": True,
        "canSubscribe": False,
        "canPublishData": False,
    }


def mint_livekit_token(
    api_key: str,
    api_secret: str,
    *,
    room: str,
    identity: str,
    name: str,
    ttl_seconds: int = TOKEN_TTL_S,
    now: float | None = None,
) -> str:
    """Mint a LiveKit access JWT (HS256) — server-side; the secret never leaves here.
    Standard LiveKit shape: the grant under the ``video`` claim, identity in ``sub``,
    a fixed bot identity (``mymts-wall-bot`` — DUPLICATE_IDENTITY cleanly evicts a
    stale connection, note K) and the display name in ``name`` (note L). Pure +
    unit-testable (inject ``now``); no PyJWT dependency."""
    issued = int(now if now is not None else time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    claims = {
        "exp": issued + ttl_seconds,
        "iss": api_key,
        "nbf": issued,
        "sub": identity,
        "name": name,
        "video": video_grant(room),
    }
    signing_input = (
        _b64url(json.dumps(header, separators=(",", ":")).encode("utf-8"))
        + "."
        + _b64url(json.dumps(claims, separators=(",", ":")).encode("utf-8"))
    )
    sig = hmac.new(api_secret.encode("utf-8"), signing_input.encode("ascii"), hashlib.sha256).digest()
    return signing_input + "." + _b64url(sig)


def room_name(channel_guid: str) -> str:
    """The LiveKit room for a Mercury channel GUID (read from config — note J, never
    hardcoded)."""
    return f"channel-{channel_guid}"


def cap_resolution(res: object) -> str:
    """Clamp a resolution name to ≤1080p (the screen-share top layer)."""
    ladder = list(supervisor.RENDER_RESOLUTIONS)
    name = res if res in ladder else "1080p"
    if ladder.index(name) > ladder.index(MERCURY_MAX_RESOLUTION):
        name = MERCURY_MAX_RESOLUTION
    return name


def build_simulcast_layers(resolution: object, bitrate_kbps: int, fps: int) -> list[dict]:
    """The FORCED screen-share simulcast ladder (note B): the top layer is the
    configured resolution (≤1080p) at the configured bitrate, plus 720p + 360p lower
    layers (only those strictly smaller than the top). Bitrates in BITS/s (the SDK's
    VideoPreset unit). Without these explicit layers a viewer's 320px mini-viewer
    would pull full res and dynacast/adaptiveStream couldn't downscale."""
    res = cap_resolution(resolution)
    w, h = supervisor.resolution_to_dimensions(res)
    layers = [{"w": w, "h": h, "bitrate": int(bitrate_kbps) * 1000, "fps": int(fps)}]
    for lw, lh, lkbps, lfps in ((1280, 720, 1500, 15), (640, 360, 500, 15)):
        if lw < w and lh < h:
            layers.append({"w": lw, "h": lh, "bitrate": lkbps * 1000, "fps": lfps})
    return layers


def build_publisher_config(
    *,
    ws_url: str,
    token: str,
    room: str,
    identity: str,
    name: str,
    audio: bool,
    fps: int,
    resolution: object,
    bitrate_kbps: int,
    hls_url: str,
) -> dict:
    """The JSON the browser fetches from the localhost control server's ``/config``.
    Contains the SHORT-LIVED token (never the API secret), the room, the HLS the
    publisher captures (render-once), and the forced simulcast layers. Pure."""
    res = cap_resolution(resolution)
    return {
        "wsUrl": ws_url,
        "token": token,
        "room": room,
        "identity": identity,
        "name": name,
        "hlsUrl": hls_url,
        "audio": bool(audio),
        "fps": int(fps),
        "topBitrate": int(bitrate_kbps) * 1000,
        "layers": build_simulcast_layers(res, bitrate_kbps, fps),
    }


def _tcp_probe(host_url: str, node_ip: str | None = None, timeout: float = 2.0) -> bool | None:
    """A real but NON-LiveKit reachability probe: a short TCP connect to the signaling
    host:port, immediately closed — no TLS, no WebSocket, no LiveKit, no token, no
    media. For the prod host it dials ``node_ip`` (the tailnet IP — the system can't
    resolve chat.mercurychat.net; only Chrome's host-resolver-rules can), so this
    confirms the tailnet path. True (reachable) / False (refused) / None (unparseable
    → "unknown"). Only ever called once key + channel are present (zero egress until
    configured)."""
    try:
        parsed = urlparse(host_url)
        host = parsed.hostname
        if not host:
            return None
        if host == PROD_HOST and node_ip:
            host = node_ip
        port = parsed.port or (443 if parsed.scheme in ("wss", "https") else 80)
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False
    except Exception:
        return None


def make_publisher(
    *, env: Mapping | None = None, log: Callable[[str], None] = print,
    display: str = ":99", chromium_bin: str = "chromium",
) -> MercuryPublisher:
    """Factory: the REAL publisher when LiveKit credentials are present, else the inert
    stub (so an un-credentialed renderer performs ZERO egress to Mercury — same posture
    as before the wire-up)."""
    e = env if env is not None else os.environ
    if e.get(ENV_API_KEY) and e.get(ENV_API_SECRET):
        return RealMercuryPublisher(env=e, log=log, display=display, chromium_bin=chromium_bin)
    return StubMercuryPublisher(env=e, log=log)


# ============================ the abstraction ============================

class MercuryPublisher(ABC):
    """The seam the renderer's output manager drives: ``configure`` with the live
    ``outputs.mercury`` config, then ``start`` / ``stop`` / ``restart``, and
    ``status`` for the status file."""

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

    # Optional: the renderer tells the publisher the canvas it should park its window
    # off of. Default no-op (the stub).
    def set_canvas(self, width: int, height: int, fps: int) -> None:  # noqa: B027
        return None


# ============================ the localhost control server ============================

class _ControlServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, addr, handler, publisher: RealMercuryPublisher) -> None:
        super().__init__(addr, handler)
        self.publisher = publisher


class _ControlHandler(BaseHTTPRequestHandler):
    """Serves the publisher assets (path-safe, own dir only), the dynamic ``/config``
    (a fresh JWT each fetch — re-mint on reload), and receives ``/status`` POSTs. All
    on 127.0.0.1 — never exposed off-host."""

    def log_message(self, *args) -> None:  # silence per-request logging
        return None

    def _send(self, code: int, body, ctype: str = "application/octet-stream") -> None:
        data = body if isinstance(body, bytes) else str(body).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def _serve_static(self, rel: str, ctype: str) -> None:
        base = Path(PUBLISHER_DIR).resolve()
        target = (base / rel)
        try:
            real = target.resolve(strict=True)
            real.relative_to(base)            # reject traversal outside PUBLISHER_DIR
            if not real.is_file():
                return self._send(404, b"not found")
            self._send(200, real.read_bytes(), ctype)
        except (OSError, ValueError):
            self._send(404, b"not found")

    _CTYPES = {".html": "text/html", ".js": "application/javascript", ".json": "application/json"}

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if path in ("/", "/publisher.html"):
            return self._serve_static("publisher.html", "text/html")
        if path == "/config":
            cfg = self.server.publisher.current_config_json()
            return self._send(200, cfg, "application/json") if cfg else self._send(503, b"not configured")
        if path == "/publisher.js":
            return self._serve_static("publisher.js", "application/javascript")
        if path.startswith("/vendor/"):
            rel = path[len("/vendor/"):]
            return self._serve_static(os.path.join("vendor", rel), "application/javascript")
        return self._send(404, b"not found")

    def do_POST(self) -> None:
        if urlparse(self.path).path == "/status":
            length = int(self.headers.get("Content-Length", "0") or 0)
            raw = self.rfile.read(length) if length else b"{}"
            try:
                payload = json.loads(raw or b"{}")
            except (ValueError, TypeError):
                payload = {}
            self.server.publisher.on_browser_status(payload if isinstance(payload, dict) else {})
            return self._send(200, b"ok")
        return self._send(404, b"not found")


# ============================ the real publisher ============================

class RealMercuryPublisher(MercuryPublisher):
    """The LiveKit screen_share publisher: a publisher Chrome on the shared Xvfb +
    ``livekit-client``, fed a server-minted JWT via a localhost control server. Single
    instance (note G/K); the browser owns transport reconnect, this owns process
    relaunch + the honest status."""

    def __init__(
        self,
        *,
        env: Mapping | None = None,
        log: Callable[[str], None] = print,
        display: str = ":99",
        chromium_bin: str = "chromium",
        probe: Callable[..., bool | None] = _tcp_probe,
    ) -> None:
        self._env = env if env is not None else os.environ
        self._log = log
        self._display = display
        self._chromium = chromium_bin
        self._probe = probe
        self._cfg: dict = {"enabled": False}
        self._proc: subprocess.Popen | None = None
        self._server: _ControlServer | None = None
        self._port: int | None = None
        self._lock = threading.Lock()
        self._browser_state: dict | None = None
        self._canvas = (1920, 1080, 30)

    # ---- MercuryPublisher interface ----
    def configure(self, mercury_output: Mapping) -> None:
        self._cfg = dict(mercury_output or {})

    def set_canvas(self, width: int, height: int, fps: int) -> None:
        self._canvas = (int(width), int(height), int(fps))

    def start(self) -> None:
        if not self._cfg.get("enabled"):
            return
        if not self._ready_to_publish():
            self._log("mercury: enabled but not configured (key/secret/channel) — staying needs_setup")
            return
        with self._lock:
            if self._proc is not None and self._proc.poll() is None:
                self._log("mercury: publisher already running (single instance)")
                return
            self._ensure_server()
            self._browser_state = {"state": STATE_CONNECTING, "ts": time.time()}
            self._proc = self._launch_chrome()
            self._log(f"mercury: publisher Chrome started (pid {self._proc.pid}) → {self._room()}")

    def stop(self) -> None:
        with self._lock:
            self._terminate_locked()
            self._browser_state = None
        self._log("mercury: publisher stopped")

    def restart(self) -> None:
        # Reconnect the publisher ONLY — render + HLS untouched (note: restart cycles
        # the publisher Chrome, re-minting the token on the fresh /config fetch).
        self.stop()
        self.start()

    def status(self) -> dict:
        c = self._cfg
        if not c.get("enabled"):
            return {
                "state": STATE_DISABLED,
                "checklist": self._checklist(probe=False),
                "detail": "Output disabled",
                "channel_guid": c.get("channel_guid", ""),
                "display_name": c.get("display_name", ""),
            }
        if not self._ready_to_publish():
            return {
                "state": STATE_NEEDS_SETUP,
                "checklist": self._checklist(probe=True),
                "detail": self._needs_setup_detail(),
                "channel_guid": c.get("channel_guid", ""),
                "display_name": c.get("display_name", ""),
            }
        bs = self._browser_state or {}
        proc_alive = self._proc is not None and self._proc.poll() is None
        state = bs.get("state", STATE_CONNECTING)
        last_error = bs.get("detail") or bs.get("error")
        if not proc_alive:
            # the Chrome exited unexpectedly while enabled+configured
            state = STATE_ERROR
            last_error = last_error or "publisher process exited"
        detail = {
            STATE_PUBLISHING: "Publishing the wall (screen_share, simulcast)",
            STATE_DYNACAST_PAUSED: "Idle — no viewers; upstream paused by dynacast (normal)",
            STATE_RECONNECTING: "Reconnecting to the SFU…",
            STATE_CONNECTED: "Connected — publishing…",
            STATE_CONNECTING: "Connecting to the SFU…",
            STATE_ERROR: last_error or "Publisher error",
        }.get(state, "")
        return {
            "state": state,
            "checklist": self._checklist(probe=True),
            "detail": detail,
            "viewer_count": bs.get("viewers"),
            "last_error": last_error if state == STATE_ERROR else None,
            "room": self._room(),
            "channel_guid": c.get("channel_guid", ""),
            "display_name": c.get("display_name", ""),
        }

    # ---- browser status sink ----
    def on_browser_status(self, payload: dict) -> None:
        # Trust only known keys; the browser posts {state, viewers, detail, error, kbps}.
        self._browser_state = {
            "state": payload.get("state", STATE_CONNECTING),
            "viewers": payload.get("viewers"),
            "detail": payload.get("detail"),
            "error": payload.get("error"),
            "kbps": payload.get("kbps"),
            "ts": time.time(),
        }

    # ---- internals ----
    def _creds(self) -> tuple[str | None, str | None]:
        return self._env.get(ENV_API_KEY), self._env.get(ENV_API_SECRET)

    def _ws_url(self) -> str:
        return self._env.get(ENV_HOST, "")

    def _identity(self) -> str:
        return self._env.get(ENV_BOT_IDENTITY) or DEFAULT_IDENTITY

    def _name(self) -> str:
        return self._cfg.get("display_name") or DEFAULT_DISPLAY_NAME

    def _room(self) -> str:
        return room_name(self._cfg.get("channel_guid", ""))

    def _ready_to_publish(self) -> bool:
        key, secret = self._creds()
        return bool(key and secret and self._cfg.get("channel_guid") and self._ws_url())

    def _checklist(self, *, probe: bool) -> dict:
        key, secret = self._creds()
        key_present = bool(key and secret)
        channel_set = bool(self._cfg.get("channel_guid"))
        tailnet: bool | None = None
        if probe and key_present and channel_set and self._ws_url():
            tailnet = self._probe(self._ws_url(), self._env.get(ENV_NODE_IP))
        return {"key_present": key_present, "channel_set": channel_set, "tailnet_reachable": tailnet}

    def _needs_setup_detail(self) -> str:
        missing = []
        key, secret = self._creds()
        if not (key and secret):
            missing.append("LiveKit key")
        if not self._cfg.get("channel_guid"):
            missing.append("channel")
        if not self._ws_url():
            missing.append("host")
        return "Waiting on setup: missing " + ", ".join(missing) if missing else "Waiting on setup"

    def current_config_json(self) -> str | None:
        """Serve a FRESH token each fetch (re-mint on reload — note 18). None when not
        configured (the control server answers 503)."""
        if not self._ready_to_publish():
            return None
        key, secret = self._creds()
        token = mint_livekit_token(
            key, secret, room=self._room(), identity=self._identity(), name=self._name()
        )
        _, _, fps = self._canvas
        cfg = build_publisher_config(
            ws_url=self._ws_url(), token=token, room=self._room(), identity=self._identity(),
            name=self._name(), audio=bool(self._cfg.get("audio")), fps=fps,
            resolution=self._cfg.get("resolution", "720p"),
            bitrate_kbps=int(self._cfg.get("bitrate_kbps", 3000)),
            hls_url=self._env.get(ENV_HLS_URL) or DEFAULT_HLS_URL,
        )
        return json.dumps(cfg)

    def _ensure_server(self) -> None:
        if self._server is not None:
            return
        self._server = _ControlServer(("127.0.0.1", 0), _ControlHandler, self)
        self._port = self._server.server_address[1]
        threading.Thread(target=self._server.serve_forever, daemon=True).start()
        self._log(f"mercury: control server on 127.0.0.1:{self._port}")

    def _host_resolver_arg(self) -> str | None:
        """note H: connect under the cert hostname but resolve it to the tailnet IP —
        Chrome's per-browser --host-resolver-rules (no /etc/hosts, no root). Only for
        the prod host with a node IP set; the dev SFU connects directly."""
        host = urlparse(self._ws_url()).hostname or ""
        node_ip = self._env.get(ENV_NODE_IP)
        if host == PROD_HOST and node_ip:
            return f"--host-resolver-rules=MAP {PROD_HOST} {node_ip}"
        return None

    def _launch_chrome(self) -> subprocess.Popen:
        w, _h, _fps = self._canvas
        url = f"http://127.0.0.1:{self._port}/publisher.html"
        args = [
            self._chromium,
            "--no-sandbox", "--no-first-run", "--no-default-browser-check",
            "--disable-gpu", "--disable-dev-shm-usage", "--disable-software-rasterizer",
            # autoplay the captured <video> with no gesture (so captureStream produces
            # frames headlessly); trust the helper's self-signed LAN cert if the HLS
            # URL is HTTPS (the default is plain-HTTP :8082, so this is belt-and-braces).
            "--autoplay-policy=no-user-gesture-required",
            "--ignore-certificate-errors",
            "--user-data-dir=/tmp/mercury-pub-profile",
            # Park the publisher's OWN window OFF the WxH framebuffer so it never
            # pollutes the wall's HLS x11grab (both target the WxH root region)...
            f"--window-position={w},0", "--window-size=400,300",
            # ...BUT an off-screen/occluded window gets its media DECODE SUSPENDED by
            # Chrome (the <video> stalls at readyState 0, captureStream produces no
            # frames). These keep the off-screen renderer fully alive so the HLS keeps
            # decoding — verified: with them the off-screen window reaches loadeddata +
            # frames; without them it stalls. (See docs/decisions/0003.)
            "--disable-backgrounding-occluded-windows", "--disable-renderer-backgrounding",
            "--disable-background-timer-throttling",
            "--disable-features=CalculateNativeWinOcclusion",
            "--disable-infobars", "--disable-notifications", "--disable-translate",
        ]
        host_arg = self._host_resolver_arg()
        if host_arg:
            args.append(host_arg)
        args.append(url)
        env = dict(self._env, DISPLAY=self._display)
        return subprocess.Popen(args, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def _terminate_locked(self) -> None:
        if self._proc is not None and self._proc.poll() is None:
            self._proc.terminate()
            try:
                self._proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self._proc.kill()
        self._proc = None


# ============================ the inert stub (no-creds fallback + tests) ============================

class StubMercuryPublisher(MercuryPublisher):
    """The zero-egress fallback when no credentials are present: opens no socket and
    mints no token; reports an honest setup checklist; short-circuits the tailnet probe
    until key + channel are present (the default — no creds — does NO network)."""

    def __init__(
        self,
        *,
        env: Mapping | None = None,
        log: Callable[[str], None] = print,
        probe: Callable[..., bool | None] = _tcp_probe,
    ) -> None:
        self._env = env if env is not None else os.environ
        self._log = log
        self._probe = probe
        self._cfg: dict = {"enabled": False}

    def configure(self, mercury_output: Mapping) -> None:
        self._cfg = dict(mercury_output or {})

    def start(self) -> None:
        self._log("mercury stub: no LiveKit credentials — not publishing (no socket, no JWT)")

    def stop(self) -> None:
        self._log("mercury stub: stop (no-op)")

    def restart(self) -> None:
        self._log("mercury stub: restart (no-op)")

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
        tailnet: bool | None = None
        if key_present and channel_set:
            tailnet = self._probe(self._env.get(ENV_HOST, ""), self._env.get(ENV_NODE_IP))
        missing = []
        if not key_present:
            missing.append("LiveKit key")
        if not channel_set:
            missing.append("channel")
        detail = "Waiting on setup: missing " + ", ".join(missing) if missing else "Waiting on setup"
        return {
            "state": STATE_NEEDS_SETUP,
            "checklist": {"key_present": key_present, "channel_set": channel_set, "tailnet_reachable": tailnet},
            "detail": detail,
        }
