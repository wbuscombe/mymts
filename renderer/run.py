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

import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import time

import supervisor

# ---- config (env, with sane defaults; no secrets) ----
HELPER_URL = os.environ.get("HELPER_URL", "https://mymts-helper:8443/app/?render=1")
STREAM_DIR = os.environ.get("STREAM_DIR", "/stream")
DISPLAY = os.environ.get("DISPLAY", ":99")
WIDTH = int(os.environ.get("RENDER_WIDTH", "1920"))
HEIGHT = int(os.environ.get("RENDER_HEIGHT", "1080"))
FPS = int(os.environ.get("RENDER_FPS", "30"))
VIDEO_BITRATE = os.environ.get("RENDER_VIDEO_BITRATE", "8M")
# VBV bufsize (~2x bitrate). These env values are the FALLBACK when the helper's
# wall config is unreachable at startup; normally the config's render.resolution
# drives WIDTH/HEIGHT/VIDEO_BITRATE/BUFSIZE (config > env > default — see
# resolve_render_dimensions()).
BUFSIZE = os.environ.get("RENDER_BUFSIZE", "16M")
AUDIO_BITRATE = os.environ.get("RENDER_AUDIO_BITRATE", "128k")
# How often the supervise loop re-checks the configured resolution (seconds).
RESOLUTION_POLL_S = 12
# Blank/frozen render detection (the 8-days-of-white lesson): sample the live X11
# render this often. A tiny grayscale probe frame is enough to measure uniformity;
# N consecutive blank/frozen samples (minutes apart) restart the render.
BLANK_SAMPLE_INTERVAL_S = int(os.environ.get("RENDER_BLANK_SAMPLE_S", "120"))
BLANK_PROBE_W, BLANK_PROBE_H = 32, 18
# The wall config the renderer reads its resolution from (derived from HELPER_URL).
API_WALL_URL = os.environ.get(
    "RENDER_API_WALL_URL", "https://mymts-helper:8443/api/wall"
)
HLS_TIME = os.environ.get("RENDER_HLS_TIME", "4")
HLS_LIST_SIZE = os.environ.get("RENDER_HLS_LIST_SIZE", "6")
X264_PRESET = os.environ.get("RENDER_X264_PRESET", "veryfast")
SINK = "mymts"   # the PulseAudio null sink name
# The per-output runtime status the renderer writes into the shared stream volume
# (renderer rw, helper ro — the SAME trust direction as the HLS stream, no new
# privileged channel). The helper reads it for /api/outputs/status.
STATUS_FILE = "outputs-status.json"
DEFAULT_BITRATE_KBPS = 8000

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


def fetch_outputs() -> dict | None:
    """The live `outputs` block from the wall config, or **None** when the helper is
    unreachable / returns no usable outputs. The POLL LOOP uses this so a helper blip
    leaves the running pipeline UNTOUCHED (the documented intent) — see below.

    THE BUG THIS FIXES (2026-07-14): the prior `read_outputs()` fell back to a
    single-HLS DEFAULT on any failed read. During the poll loop that default DIFFERS
    from the live config (its bitrate/epoch, and it drops the discord encoder output),
    so `plan_output_restart` saw a "change" and RESPAWNED the encoder — then the next
    successful read differed from the fallback and respawned again. Under NAS load the
    1-CPU helper answers slowly, so failed reads recur and the encoder flapped every
    poll (127 respawns / 6h observed) → stream wedges → full-stack restarts → a
    self-amplifying load spiral. Returning None (not a divergent config) on a blip and
    having the loop SKIP reconfiguration matches the loop's own comment: "An
    unreachable helper → no change → no restart." """
    cfg = get_json(API_WALL_URL)
    if isinstance(cfg, dict) and isinstance(cfg.get("outputs"), dict) and cfg["outputs"]:
        return cfg["outputs"]
    return None


def read_outputs() -> dict:
    """STARTUP read: the live outputs, or a safe single-enabled-HLS fallback so the
    renderer can boot even before the helper answers (Config > env > default). The
    poll loop uses `fetch_outputs()` + skips reconfiguration on a None, so a later
    blip never flaps the running pipeline (unlike this fallback, which is a DIFFERENT
    config used only for the cold-start canvas)."""
    return fetch_outputs() or {
        "hls": {
            "enabled": True, "resolution": supervisor.DEFAULT_RESOLUTION,
            "bitrate_kbps": DEFAULT_BITRATE_KBPS, "audio": True, "restart_epoch": 0,
        }
    }


def resolve_render_dimensions(outputs: dict | None = None) -> tuple[int, int, int]:
    """(width, height, fps) for the DERIVED render canvas — the largest enabled
    output resolution (every output downscales from the single capture). The fps is
    that resolution's SUSTAINABLE rate (a high-res wall runs at a steady lower fps,
    not a juddery 30 — §31). Reads `outputs` if not supplied."""
    outputs = outputs if outputs is not None else read_outputs()
    res = supervisor.derive_render_resolution(outputs)
    w, h = supervisor.resolution_to_dimensions(res)
    return w, h, supervisor.resolution_to_fps(res)


def encoder_specs(outputs: dict) -> list[dict]:
    """Per enabled ENCODER output, the spec the fan-out
    ffmpeg needs: its downscale dims (from its resolution), bitrate, whether to mux
    the wall audio, and its HLS output paths. The `hls` output writes to the
    STREAM_DIR ROOT (the existing VLC playlist + segments — regression-preserved);
    any other encoder output writes to a per-output subdir."""
    specs: list[dict] = []
    for name, o in supervisor.enabled_encoder_outputs(outputs).items():
        w, h = supervisor.resolution_to_dimensions(o.get("resolution"))
        if name == "hls":
            playlist = os.path.join(STREAM_DIR, supervisor.PLAYLIST_NAME)
            segments = os.path.join(STREAM_DIR, "seg_%05d.ts")
        else:
            subdir = os.path.join(STREAM_DIR, name)
            os.makedirs(subdir, exist_ok=True)
            playlist = os.path.join(subdir, supervisor.PLAYLIST_NAME)
            segments = os.path.join(subdir, "seg_%05d.ts")
        specs.append({
            "name": name, "w": w, "h": h,
            "bitrate_kbps": int(o.get("bitrate_kbps", DEFAULT_BITRATE_KBPS)),
            "audio": bool(o.get("audio", True)),
            "playlist": playlist, "segments": segments,
        })
    return specs


def fanout_cmd(outputs: dict) -> list[str]:
    """The ONE capture-once → encode-N ffmpeg argv for the currently-enabled encoder
    outputs (built off the current WIDTH/HEIGHT/FPS canvas)."""
    return supervisor.build_capture_fanout_cmd(
        render_w=WIDTH, render_h=HEIGHT, fps=FPS, display=DISPLAY, sink=SINK,
        specs=encoder_specs(outputs), grab_queue=supervisor.grab_queue_size(WIDTH, HEIGHT),
        x264_preset=X264_PRESET, hls_time=HLS_TIME, hls_list_size=HLS_LIST_SIZE,
        audio_bitrate=AUDIO_BITRATE,
    )


def write_status_file(
    outputs: dict, render_res: str, ffmpeg_running: bool
) -> None:
    """Write the per-output runtime status into the shared stream volume (atomic
    rename). HLS: running/stopped/disabled + effective res/bitrate + the playlist
    path. The helper reads
    this (read-only) for /api/outputs/status — no new privileged channel."""
    status: dict = {
        "render": {"resolution": render_res, "width": WIDTH, "height": HEIGHT, "fps": FPS},
        "updated_at": time.time(),
        "outputs": {},
    }
    for name, o in outputs.items():
        enabled = bool(o.get("enabled"))
        entry = {
            "state": "running" if (enabled and ffmpeg_running) else ("stopped" if enabled else "disabled"),
            "resolution": o.get("resolution"),
            "bitrate_kbps": o.get("bitrate_kbps"),
            "audio": bool(o.get("audio")),
        }
        if name == "hls":
            entry["playlist_path"] = "/api/stream/playlist.m3u8"
        status["outputs"][name] = entry
    path = os.path.join(STREAM_DIR, STATUS_FILE)
    fd, tmp = tempfile.mkstemp(dir=STREAM_DIR, prefix=".status.", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(status, f)
        # mkstemp creates 0600; the helper (a DIFFERENT uid, ro on this volume) must
        # read it — make it world-readable like ffmpeg's .ts segments (0644). The
        # file is non-secret runtime status.
        os.chmod(tmp, 0o644)
        os.replace(tmp, path)
    except OSError:
        try:
            os.unlink(tmp)
        except OSError:
            pass


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
        # the page's render-mode cursor:none). The grab queue depth is bounded by
        # the raw-frame MEMORY at THIS resolution (deep at low res, shallow as the
        # canvas grows) so a behind-the-encoder stream at ANY ladder rung drops
        # frames within the mem_limit instead of OOM-ballooning the raw-frame queue.
        "-thread_queue_size", str(supervisor.grab_queue_size(WIDTH, HEIGHT)),
        "-f", "x11grab", "-draw_mouse", "0", "-framerate", str(FPS),
        "-video_size", f"{WIDTH}x{HEIGHT}", "-i", DISPLAY,
        # audio: the null sink's monitor (the audible cell's audio).
        "-thread_queue_size", "1024",
        "-f", "pulse", "-i", f"{SINK}.monitor",
        # REAL-TIME encode: -tune zerolatency disables B-frames + the lookahead
        # buffer (sliced-threads, no frame reordering) so the encoder never falls
        # behind the live capture and adds minimal latency — the right profile for
        # a continuous screen-grab feed (vs the default, which buffers frames for
        # compression and can judder/lag under a load spike).
        "-c:v", "libx264", "-preset", X264_PRESET, "-tune", "zerolatency",
        "-pix_fmt", "yuv420p",
        "-g", str(FPS * 2), "-b:v", VIDEO_BITRATE, "-maxrate", VIDEO_BITRATE,
        "-bufsize", BUFSIZE,
        # CONSTANT framerate end-to-end: x11grab samples at FPS, and -fps_mode cfr
        # + -r FPS pace the OUTPUT to exactly FPS by duplicating/dropping, so the
        # encoded motion (esp. the continuous ticker crawl) is evenly timed — no
        # judder from grab/encode rate jitter. NOTE: the browser's software render
        # rate caps the UNIQUE content (smooth ~30fps at 1080p; render-bound above
        # it — see ARCHITECTURE §31), which no encode setting can lift (needs GPU).
        "-fps_mode", "cfr", "-r", str(FPS),
        "-c:a", "aac", "-b:a", AUDIO_BITRATE, "-ar", "44100",
        "-f", "hls", "-hls_time", HLS_TIME, "-hls_list_size", HLS_LIST_SIZE,
        "-hls_flags", "delete_segments+append_list+independent_segments",
        "-hls_segment_type", "mpegts",
        "-hls_segment_filename", os.path.join(STREAM_DIR, "seg_%05d.ts"),
        os.path.join(STREAM_DIR, supervisor.PLAYLIST_NAME),
    ]


def sample_render_frame() -> bytes | None:
    """Grab ONE frame straight from the live X11 display, downscaled to a tiny
    grayscale buffer, for blank/frozen detection. This sees what Chromium is ACTUALLY
    rendering (the white-wedge symptom), independent of the HLS output. Returns the
    raw gray bytes (BLANK_PROBE_W*H), or None on any ffmpeg error (a failed probe is
    NOT evidence — the caller skips that round rather than acting on nothing)."""
    try:
        out = subprocess.run(
            ["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin",
             "-f", "x11grab", "-video_size", f"{WIDTH}x{HEIGHT}", "-i", DISPLAY,
             "-frames:v", "1", "-vf", f"scale={BLANK_PROBE_W}:{BLANK_PROBE_H},format=gray",
             "-f", "rawvideo", "-"],
            capture_output=True, timeout=15,
        )
    except (subprocess.TimeoutExpired, OSError):
        return None
    if out.returncode == 0 and len(out.stdout) == BLANK_PROBE_W * BLANK_PROBE_H:
        return out.stdout
    return None


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
    # Reach the helper FIRST so we can read the configured outputs + derive the
    # render resolution BEFORE sizing Xvfb (its screen size is fixed at start; a
    # canvas change restarts the whole stack). Pulse/Xvfb/helper are otherwise
    # independent, so this reorder is safe.
    wait_for_helper(HELPER_URL)
    outputs = read_outputs()
    global WIDTH, HEIGHT, FPS
    WIDTH, HEIGHT, FPS = resolve_render_dimensions(outputs)
    render_res = supervisor.derive_render_resolution(outputs)
    log(f"render canvas {WIDTH}x{HEIGHT} @ {FPS}fps (derived from outputs: {render_res})")
    xvfb = start_xvfb()

    env = dict(os.environ, DISPLAY=DISPLAY, PULSE_SINK=SINK)

    chromium = Child("chromium", chromium_cmd(), env)
    chromium.start()
    # ONE fan-out ffmpeg: capture the composited wall once, encode per enabled
    # encoder output. (No encoder output enabled → no ffmpeg; the render still runs.)
    specs = encoder_specs(outputs)
    ffmpeg = Child("ffmpeg", fanout_cmd(outputs), env) if specs else None
    if ffmpeg:
        ffmpeg.start()
    children = [chromium] + ([ffmpeg] if ffmpeg else [])

    enc_names = [s["name"] for s in specs]
    log(f"streaming {HELPER_URL} → outputs {enc_names or '(none)'}")
    write_status_file(outputs, render_res, ffmpeg is not None)
    last_fresh = None   # monotonic time the stream was last HEALTHY (advancing)
    next_check = time.monotonic() + RESOLUTION_POLL_S
    # Blank/frozen render watchdog: sample the live render every ~2 min; N consecutive
    # blank/frozen samples restart Chromium (then the whole stack if it recurs) —
    # catching a wedged white/static page ffmpeg is happily encoding (the 8-day
    # incident the stale check missed, because that blank stream stayed 'fresh').
    blank_detector = supervisor.BlankOutputDetector()
    blank_tracker = supervisor.RestartTracker(window_seconds=1800.0, max_in_window=3)
    next_blank_check = time.monotonic() + BLANK_SAMPLE_INTERVAL_S
    while not _stop:
        # Xvfb is foundational — if it dies, the whole stack is broken; exit so
        # the container restarts cleanly (compose restart: unless-stopped).
        if xvfb.poll() is not None:
            log("Xvfb exited — restarting the container stack")
            for c in children:
                c.terminate()
            return 1
        # OUTPUTS change (from /control/): apply the restart MATRIX. A change that
        # moves the derived canvas restarts the whole stack (the heavy path); a
        # change to only an encoder's bitrate/audio/resolution-not-moving-max
        # respawns just the fan-out ffmpeg (render untouched). An unreachable helper
        # → no change → no restart.
        if time.monotonic() >= next_check:
            next_check = time.monotonic() + RESOLUTION_POLL_S
            # A helper blip returns None → keep the running pipeline UNTOUCHED (no
            # plan, no respawn). ONLY a real, freshly-read config drives a restart —
            # so a slow/unreachable helper can never flap the encoders (the 2026-07-14
            # churn fix; see fetch_outputs).
            new_outputs = fetch_outputs()
            if new_outputs is not None:
                plan = supervisor.plan_output_restart(outputs, new_outputs)
                if plan["render_restart"]:
                    new_res = supervisor.derive_render_resolution(new_outputs)
                    log(f"render canvas changed {render_res} → {new_res} — restarting the container stack")
                    for c in children:
                        c.terminate()
                    if xvfb.poll() is None:
                        xvfb.terminate()
                    return 1
                if plan["encoder_restart"]:
                    log("encoder outputs changed — respawning the capture/encode (render untouched)")
                    if ffmpeg:
                        ffmpeg.terminate()
                    specs = encoder_specs(new_outputs)
                    ffmpeg = Child("ffmpeg", fanout_cmd(new_outputs), env) if specs else None
                    if ffmpeg:
                        ffmpeg.start()
                    children = [chromium] + ([ffmpeg] if ffmpeg else [])
                outputs = new_outputs
                write_status_file(outputs, render_res, ffmpeg is not None)
        # WEDGE detection: ffmpeg/Chromium can hang (alive but the stream stops
        # advancing). Only meaningful when an HLS encoder is running (it writes the
        # playlist this checks). Once healthy, a stale-past-threshold stack restarts.
        if ffmpeg:
            healthy = supervisor.is_stream_healthy(STREAM_DIR, time.time())
            if healthy:
                last_fresh = time.monotonic()
            elif supervisor.stale_stack_restart(last_fresh, time.monotonic(), healthy):
                log("stream wedged (stale while processes alive) — restarting the container stack")
                for c in children:
                    c.terminate()
                return 1
        # BLANK/FROZEN render detection: complements the wedge check above (which only
        # catches a stream that STOPS advancing). A blank-BUT-advancing stream — Chromium
        # wedged on a white/frozen page ffmpeg happily encodes — is exactly the 8-day
        # incident. Only sampled when an encoder is running AND the stream is advancing
        # (otherwise the stale-wedge path owns it). N consecutive blank/frozen samples
        # restart Chromium first, escalating to the full stack if it recurs.
        if ffmpeg and time.monotonic() >= next_blank_check:
            next_blank_check = time.monotonic() + BLANK_SAMPLE_INTERVAL_S
            if supervisor.is_stream_healthy(STREAM_DIR, time.time()):
                frame = sample_render_frame()
                if frame is not None and blank_detector.record(frame):
                    blank_tracker.record(time.monotonic())
                    if blank_tracker.is_crash_looping(time.monotonic()):
                        log("render blank/frozen recurring after Chromium restarts — restarting the container stack")
                        for c in children:
                            c.terminate()
                        return 1
                    log("render blank/frozen (Chromium wedged on a uniform/static page) — restarting Chromium")
                    chromium.terminate()
                    chromium.start()
                    blank_detector.reset()
            else:
                blank_detector.reset()   # not advancing → the stale-wedge path owns it
        for c in children:
            rc = c.poll()
            if rc is not None:
                # monotonic: crash-loop windowing is an INTERVAL measure, immune
                # to wall-clock/NTP jumps (segment freshness uses wall-clock).
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
