#!/usr/bin/env python3
"""MyMTS renderer runtime — Xvfb + Chromium (`/app/`) + ffmpeg → HLS.

Runs the config-driven web wall on a virtual display and streams the composited
video + the audible cell's audio as HLS, so VLC (Apple TV / any player) can open
one URL and watch the whole wall — steered live by `/control/` (the page
self-polls `/api/wall`, so a pick is reflected with NO restart).

Pipeline:
  PulseAudio null sink  ← Chromium audio (the single audible cell)
        │ .monitor
        ▼
  ffmpeg  ◄── x11grab :99 ◄── Chromium (kiosk, render mode) ◄── Xvfb :99
        ▼
  HLS (playlist.m3u8 + rolling .ts segments) in $STREAM_DIR  →  helper serves it

Foundational processes (Pulse, Xvfb) start once; the two stream processes
(Chromium, ffmpeg) are supervised + restarted with backoff (pure policy in
`supervisor.py`). A rapid crash-loop escalates to a full stack restart. All
egress (the helper + the public HLS the tiles play) takes the SAME residential
WAN path as the helper. No baked secrets; config via env.
"""

from __future__ import annotations

import os
import shutil
import signal
import subprocess
import sys
import time

import supervisor

# ---- config (env, with sane defaults; no secrets) ----
HELPER_URL = os.environ.get("HELPER_URL", "https://mymts-helper:8443/app/?render=1")
STREAM_DIR = os.environ.get("STREAM_DIR", "/stream")
DISPLAY = os.environ.get("DISPLAY", ":99")
WIDTH = int(os.environ.get("RENDER_WIDTH", "1920"))
HEIGHT = int(os.environ.get("RENDER_HEIGHT", "1080"))
FPS = int(os.environ.get("RENDER_FPS", "30"))
VIDEO_BITRATE = os.environ.get("RENDER_VIDEO_BITRATE", "6M")
# VBV bufsize (~2x bitrate). These env values are the FALLBACK when the helper's
# wall config is unreachable at startup; normally the config's render.resolution
# drives WIDTH/HEIGHT/VIDEO_BITRATE/BUFSIZE (config > env > default — see
# resolve_render_dimensions()).
BUFSIZE = os.environ.get("RENDER_BUFSIZE", "12M")
AUDIO_BITRATE = os.environ.get("RENDER_AUDIO_BITRATE", "128k")
# How often the supervise loop re-checks the configured resolution (seconds).
RESOLUTION_POLL_S = 12
# The wall config the renderer reads its resolution from (derived from HELPER_URL).
API_WALL_URL = os.environ.get(
    "RENDER_API_WALL_URL", "https://mymts-helper:8443/api/wall"
)
HLS_TIME = os.environ.get("RENDER_HLS_TIME", "4")
HLS_LIST_SIZE = os.environ.get("RENDER_HLS_LIST_SIZE", "6")
X264_PRESET = os.environ.get("RENDER_X264_PRESET", "veryfast")
SINK = "mymts"   # the PulseAudio null sink name

CHROMIUM_BIN = shutil.which("chromium") or shutil.which("chromium-browser") or "chromium"


def log(msg: str) -> None:
    print(f"[renderer] {msg}", flush=True)


def run_quiet(cmd: list[str]) -> int:
    return subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode


def ensure_runtime_dir() -> None:
    """PulseAudio + Chromium want a private XDG_RUNTIME_DIR for their sockets;
    a headless container has none, so create one on tmpfs (/tmp) owned 0700 by
    the non-root user before anything starts."""
    xdg = os.environ.get("XDG_RUNTIME_DIR") or f"/tmp/xdg-{os.getuid()}"
    os.makedirs(xdg, mode=0o700, exist_ok=True)
    os.chmod(xdg, 0o700)
    os.environ["XDG_RUNTIME_DIR"] = xdg


def start_pulse() -> None:
    """Start a user PulseAudio daemon with a NULL sink — Chromium plays into it,
    ffmpeg captures its `.monitor`. `--exit-idle-time=-1` keeps it alive with no
    real audio device (a headless container has none)."""
    os.environ.setdefault("PULSE_SINK", SINK)
    run_quiet(["pulseaudio", "--kill"])  # idempotent: clear any stale daemon
    time.sleep(0.3)
    subprocess.Popen(
        ["pulseaudio", "--exit-idle-time=-1", "--disallow-exit", "-n",
         "-L", "module-native-protocol-unix",
         "-L", f"module-null-sink sink_name={SINK} sink_properties=device.description=MyMTS"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    # wait for the sink to exist, then make it the default so Chromium uses it
    for _ in range(50):
        if run_quiet(["pactl", "list", "short", "sinks"]) == 0:
            if SINK in subprocess.run(["pactl", "list", "short", "sinks"],
                                      capture_output=True, text=True).stdout:
                break
        time.sleep(0.2)
    run_quiet(["pactl", "set-default-sink", SINK])
    log("pulseaudio null sink ready")


def wait_for_helper(url: str, timeout_s: float = 120.0) -> None:
    """Poll the helper URL until it answers, so Chromium loads the real /app/
    rather than a connection-refused error page (the renderer may start before
    the helper finishes booting). Self-signed cert is fine over the LAN — we only
    want reachability."""
    import ssl
    import urllib.request

    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=5, context=ctx) as r:
                if r.status < 500:
                    log(f"helper reachable ({url})")
                    return
        except Exception:
            pass
        time.sleep(2)
    log(f"WARNING: helper not reachable after {timeout_s:.0f}s — starting anyway")


def get_json(url: str, timeout_s: float = 5.0) -> dict | None:
    """GET a JSON document from the helper over the LAN (self-signed cert → no
    verify, same posture as wait_for_helper). Returns the parsed object, or None
    on ANY error — the caller degrades (a helper hiccup never bricks the renderer).
    Used to read the wall config's render.resolution."""
    import json
    import ssl
    import urllib.request

    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    try:
        with urllib.request.urlopen(url, timeout=timeout_s, context=ctx) as r:
            if r.status >= 400:
                return None
            return json.loads(r.read().decode("utf-8"))
    except Exception:
        return None


def resolve_render_dimensions() -> tuple[int, int, str, str]:
    """(width, height, video_bitrate, bufsize) for the CURRENTLY-configured render
    resolution. Reads the wall config's `render.resolution` and maps it via the
    pure supervisor table; on an unreachable helper / absent field it falls back to
    the current module globals (the env defaults at startup, the running values
    later) so a helper blip never changes the canvas. Config > env > default."""
    cfg = get_json(API_WALL_URL)
    res = None
    if isinstance(cfg, dict) and isinstance(cfg.get("render"), dict):
        res = cfg["render"].get("resolution")
    if res in supervisor.RENDER_RESOLUTIONS:
        w, h = supervisor.resolution_to_dimensions(res)
        bitrate, bufsize = supervisor.resolution_to_bitrate(res)
        return w, h, bitrate, bufsize
    return WIDTH, HEIGHT, VIDEO_BITRATE, BUFSIZE


def start_xvfb() -> subprocess.Popen:
    p = subprocess.Popen(
        ["Xvfb", DISPLAY, "-screen", "0", f"{WIDTH}x{HEIGHT}x24", "-nolisten", "tcp", "-dpi", "96"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    # wait for the display socket
    sock = f"/tmp/.X11-unix/X{DISPLAY.lstrip(':')}"
    for _ in range(50):
        if os.path.exists(sock):
            break
        time.sleep(0.2)
    log(f"Xvfb up on {DISPLAY} ({WIDTH}x{HEIGHT})")
    return p


def chromium_cmd() -> list[str]:
    # The page is OUR OWN trusted /app/ (same-origin, no user input), so
    # --no-sandbox in this isolated container is acceptable; --ignore-certificate
    # -errors trusts the helper's self-signed LAN cert. --disable-gpu forces
    # software compositing INTO the X framebuffer that x11grab captures.
    return [
        CHROMIUM_BIN,
        "--no-sandbox", "--no-first-run", "--no-default-browser-check",
        "--disable-gpu", "--disable-dev-shm-usage", "--disable-software-rasterizer",
        "--autoplay-policy=no-user-gesture-required",
        # The wall sizes everything off the --u CSS unit (min ~8 design-px); pin
        # Chromium's minimum font size to 0 so it can NEVER clamp a small label UP
        # (which would break the proportional scale at 4K). Belt-and-suspenders —
        # nothing renders below ~8px at 1080p / ~16px at 4K anyway.
        "--blink-settings=minimumFontSize=0,minimumLogicalFontSize=0",
        "--ignore-certificate-errors",
        "--kiosk", "--start-fullscreen",
        f"--window-size={WIDTH},{HEIGHT}", "--window-position=0,0",
        "--force-device-scale-factor=1", "--hide-scrollbars",
        "--disable-infobars", "--disable-notifications", "--disable-translate",
        "--check-for-update-interval=31536000",
        "--user-data-dir=/tmp/chromium-profile",
        HELPER_URL,
    ]


def ffmpeg_cmd() -> list[str]:
    return [
        "ffmpeg", "-hide_banner", "-loglevel", "warning", "-nostdin",
        # Larger capture queues so a transient decode/encode load spike (the
        # heavy part is software-decoding N video tiles) buffers instead of
        # dropping frames or blocking the grab — robustness under the exact load
        # the cell count drives.
        # video: the X framebuffer, cursor suppressed (belt-and-suspenders with
        # the page's render-mode cursor:none). The grab queue is RESOLUTION-bound
        # (deep at 1080p, shallow at 4K) so a behind-the-encoder 4K stream drops
        # frames within the mem_limit instead of OOM-ballooning the raw-frame queue.
        "-thread_queue_size", str(supervisor.grab_queue_size(WIDTH)),
        "-f", "x11grab", "-draw_mouse", "0", "-framerate", str(FPS),
        "-video_size", f"{WIDTH}x{HEIGHT}", "-i", DISPLAY,
        # audio: the null sink's monitor (the audible cell's audio).
        "-thread_queue_size", "1024",
        "-f", "pulse", "-i", f"{SINK}.monitor",
        "-c:v", "libx264", "-preset", X264_PRESET, "-pix_fmt", "yuv420p",
        "-g", str(FPS * 2), "-b:v", VIDEO_BITRATE, "-maxrate", VIDEO_BITRATE,
        "-bufsize", BUFSIZE,
        "-c:a", "aac", "-b:a", AUDIO_BITRATE, "-ar", "44100",
        "-f", "hls", "-hls_time", HLS_TIME, "-hls_list_size", HLS_LIST_SIZE,
        "-hls_flags", "delete_segments+append_list+independent_segments",
        "-hls_segment_type", "mpegts",
        "-hls_segment_filename", os.path.join(STREAM_DIR, "seg_%05d.ts"),
        os.path.join(STREAM_DIR, supervisor.PLAYLIST_NAME),
    ]


class Child:
    """A supervised stream process (chromium / ffmpeg) + its restart tracker."""

    def __init__(self, name: str, argv: list[str], env: dict[str, str] | None = None):
        self.name = name
        self.argv = argv
        self.env = env
        self.proc: subprocess.Popen | None = None
        self.restarts = 0
        self.tracker = supervisor.RestartTracker()

    def start(self) -> None:
        log(f"starting {self.name}")
        self.proc = subprocess.Popen(self.argv, env=self.env)

    def poll(self) -> int | None:
        return self.proc.poll() if self.proc else None

    def terminate(self) -> None:
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.proc.kill()


_stop = False


def _handle_signal(signum, frame):
    global _stop
    _stop = True


def main() -> int:
    signal.signal(signal.SIGTERM, _handle_signal)
    signal.signal(signal.SIGINT, _handle_signal)
    os.makedirs(STREAM_DIR, exist_ok=True)

    ensure_runtime_dir()
    start_pulse()
    # Reach the helper FIRST so we can read the configured render resolution
    # BEFORE sizing Xvfb (Xvfb's screen size is fixed at start; it can't resize
    # live — a resolution change restarts the whole stack). Pulse/Xvfb/helper are
    # otherwise independent, so this reorder is safe.
    wait_for_helper(HELPER_URL)
    global WIDTH, HEIGHT, VIDEO_BITRATE, BUFSIZE
    WIDTH, HEIGHT, VIDEO_BITRATE, BUFSIZE = resolve_render_dimensions()
    log(f"render canvas {WIDTH}x{HEIGHT} @ {VIDEO_BITRATE} (bufsize {BUFSIZE})")
    xvfb = start_xvfb()

    env = dict(os.environ, DISPLAY=DISPLAY, PULSE_SINK=SINK)
    children = [
        Child("chromium", chromium_cmd(), env),
        Child("ffmpeg", ffmpeg_cmd(), env),
    ]
    for c in children:
        c.start()

    log(f"streaming {HELPER_URL} → {STREAM_DIR}/{supervisor.PLAYLIST_NAME}")
    last_fresh = None   # monotonic time the stream was last HEALTHY (advancing)
    next_res_check = time.monotonic() + RESOLUTION_POLL_S
    while not _stop:
        # Xvfb is foundational — if it dies, the whole stack is broken; exit so
        # the container restarts cleanly (compose restart: unless-stopped).
        if xvfb.poll() is not None:
            log("Xvfb exited — restarting the container stack")
            for c in children:
                c.terminate()
            return 1
        # RESOLUTION change (from /control/): the canvas size is a process-start
        # param for Xvfb/Chromium/ffmpeg and can't change live, so when the config
        # picks a new resolution we restart the stack (the SAME self-heal path as
        # Xvfb-death) and it comes back at the new canvas. An unreachable helper
        # falls back to the running dims → no spurious restart.
        if time.monotonic() >= next_res_check:
            next_res_check = time.monotonic() + RESOLUTION_POLL_S
            want_w, want_h, _, _ = resolve_render_dimensions()
            if (want_w, want_h) != (WIDTH, HEIGHT):
                log(f"render resolution changed {WIDTH}x{HEIGHT} → {want_w}x{want_h} — restarting the container stack")
                for c in children:
                    c.terminate()
                if xvfb.poll() is None:
                    xvfb.terminate()
                return 1
        # WEDGE detection: ffmpeg/Chromium can hang (alive but the stream stops
        # advancing — "frames duplicated" / blocked pulse queue), which poll()
        # never catches and which leaves a player at "please wait". Once the
        # stream has been healthy, if it goes stale past the threshold, restart
        # the stack so it self-heals instead of streaming a frozen frame forever.
        healthy = supervisor.is_stream_healthy(STREAM_DIR, time.time())
        if healthy:
            last_fresh = time.monotonic()
        elif supervisor.stale_stack_restart(last_fresh, time.monotonic(), healthy):
            log("stream wedged (stale while processes alive) — restarting the container stack")
            for c in children:
                c.terminate()
            return 1
        for c in children:
            rc = c.poll()
            if rc is not None:
                # monotonic: crash-loop windowing is an INTERVAL measure, immune
                # to wall-clock/NTP jumps (segment freshness, by contrast, must
                # use wall-clock time.time() to compare against file mtimes).
                c.tracker.record(time.monotonic())
                if c.tracker.is_crash_looping(time.monotonic()):
                    log(f"{c.name} is crash-looping — restarting the container stack")
                    for x in children:
                        x.terminate()
                    return 1
                delay = supervisor.next_backoff_seconds(c.restarts)
                c.restarts += 1
                log(f"{c.name} exited rc={rc}; restart #{c.restarts} in {delay:.0f}s")
                time.sleep(delay)
                if _stop:
                    break
                c.start()
        time.sleep(1)

    log("shutting down")
    for c in children:
        c.terminate()
    if xvfb.poll() is None:
        xvfb.terminate()
    return 0


if __name__ == "__main__":
    sys.exit(main())
