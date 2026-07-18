"""Pure supervision logic for the MyMTS renderer (headless-container version).

The renderer runs Xvfb + Chromium (the config-driven `/app/` wall) + ffmpeg
(`x11grab` + a PulseAudio `.monitor`) → HLS. This module holds the PURE,
side-effect-free decisions the runtime (`run.py`) and the health check
(`healthcheck.py`) rely on, so they are unit-tested without spawning Chromium:

  - stream freshness (is ffmpeg writing fresh segments?),
  - the restart backoff + give-up policy for a crashing child.

No subprocess, no network — just `os`/time math over the stream dir.
"""

from __future__ import annotations

import os
from pathlib import Path

# A healthy stream's newest segment must be younger than this. HLS segments are
# ~4s; 20s allows a couple of missed writes before we call the stream stalled.
DEFAULT_MAX_SEGMENT_AGE_S = 20.0

PLAYLIST_NAME = "playlist.m3u8"
SEGMENT_SUFFIX = ".ts"

# ---- render resolution (mirrors helper wall/store.py RENDER_RESOLUTIONS) ----
# The wall config stores a resolution NAME; the renderer maps it to the Xvfb/
# ffmpeg canvas + the encode bitrate. Both targets are 16:9. 4K is ~4x the pixels
# and a much heavier software x264 encode (the renderer is CPU-only, cpus-capped)
# — so it gets a higher bitrate/bufsize but the SAME preset (a slower preset would
# blow the real-time frame budget and wedge the stream). 1080p is the default.
DEFAULT_RESOLUTION = "1080p"
# resolution name → (width, height). A fine 16:9 ladder; all dims even (yuv420p).
# The web wall scales to any of these via its --ux unit (min(w/1920, h/1080)).
RENDER_RESOLUTIONS = {
    "720p": (1280, 720),
    "900p": (1600, 900),
    "1080p": (1920, 1080),
    "1260p": (2240, 1260),
    "1440p": (2560, 1440),
    "1620p": (2880, 1620),
    "1800p": (3200, 1800),
    "2160p": (3840, 2160),
}
# resolution name → the SUSTAINABLE constant frame rate on this CPU-only renderer.
# From the smoothness envelope (ARCHITECTURE §31): software render time per frame
# grows ~linearly with canvas pixels + a fixed floor, so fps falls SUBLINEARLY
# above the ~1080p/30 ceiling. Targeting a resolution's sustainable CFR (e.g. 1440p
# at a steady 20fps) keeps a high-res wall CONSISTENT rather than juddery-at-30
# (which would drop frames the browser can't render). ≤1080p is capped at 30.
RENDER_FPS = {
    "720p": 30, "900p": 30, "1080p": 30, "1260p": 24,
    "1440p": 20, "1620p": 16, "1800p": 14, "2160p": 10,
}
# resolution name → (video bitrate, vbv bufsize ~= 2x). Scales with the canvas to
# carry the wall's MOTION (a full-width ticker crawl + N tiles) and offset -tune
# zerolatency's lower compression, so motion isn't crushed into blocky judder.
RENDER_BITRATES = {
    "720p": ("4M", "8M"),
    "900p": ("6M", "12M"),
    "1080p": ("8M", "16M"),
    "1260p": ("10M", "20M"),
    "1440p": ("12M", "24M"),
    "1620p": ("14M", "28M"),
    "1800p": ("16M", "32M"),
    "2160p": ("20M", "40M"),
}


def normalize_resolution(res: object) -> str:
    """Coerce a resolution name to a known key (unknown/None → the 1080p default).
    Pure — the renderer reads the config value through this so a typo/old field
    never picks an invalid canvas."""
    return res if res in RENDER_RESOLUTIONS else DEFAULT_RESOLUTION


def resolution_to_dimensions(res: object) -> tuple[int, int]:
    """(width, height) for a resolution name; unknown → 1080p. Pure."""
    return RENDER_RESOLUTIONS[normalize_resolution(res)]


def resolution_to_fps(res: object) -> int:
    """The sustainable constant frame rate for a resolution; unknown → 1080p (30).
    Pure."""
    return RENDER_FPS[normalize_resolution(res)]


def resolution_to_bitrate(res: object) -> tuple[str, str]:
    """(video_bitrate, bufsize) for a resolution name; unknown → 1080p. Pure."""
    return RENDER_BITRATES[normalize_resolution(res)]


# Cap the x11grab raw-frame buffer to this many BYTES regardless of resolution, so
# a transient encoder stall drops frames within the container mem_limit instead of
# OOM-ballooning the queue. ~1 GiB leaves ample headroom under mem_limit:4g for
# Chromium + the encoder. (A raw frame is W·H·4 bytes — 8 MB at 1080p, 33 MB at 4K.)
GRAB_QUEUE_MEM_BUDGET = 1 << 30


def grab_queue_size(width: int, height: int | None = None) -> int:
    """The x11grab INPUT queue depth (frames), bounded by the raw-frame MEMORY at
    THIS resolution (not a single 4K threshold). A deep queue would let a slow
    software encoder balloon the raw-frame buffer to many GB and OOM-kill ffmpeg
    (rc=-9) into a crash-loop — observed live at 4K (ARCHITECTURE §31). Scaling the
    depth with frame size keeps the worst-case buffer ≤ GRAB_QUEUE_MEM_BUDGET at
    EVERY ladder rung (so a transient stall DROPS frames, stream still advancing,
    no OOM), while keeping a generous depth at low res (a multi-second spike
    buffer). Clamped to [32, 1024]. height defaults to 16:9 from width. Pure."""
    if height is None:
        height = width * 9 // 16
    frame_bytes = max(1, width * height * 4)
    return max(32, min(1024, GRAB_QUEUE_MEM_BUDGET // frame_bytes))


# ---- multi-output fan-out (capture once, encode N) ----
# The expensive stage on this CPU-only box is GPU-less COMPOSITING (Chromium),
# established in the smoothness pass (§31) — NOT the encode. So the wall is
# composited ONCE at the derived render resolution and fanned out to per-output
# encodes, each downscaling from the single capture to its own resolution/bitrate.
# PUBLISHER_OUTPUTS names any output routed to a publisher abstraction instead of an
# ffmpeg → HLS encoder. Currently EMPTY (the Mercury publisher was removed in PR-018);
# the seam is kept so a future non-encoder output slots in without reworking the fan-out.
PUBLISHER_OUTPUTS: frozenset[str] = frozenset()


def is_publisher_output(name: str) -> bool:
    return name in PUBLISHER_OUTPUTS


def is_encoder_output(name: str) -> bool:
    return name not in PUBLISHER_OUTPUTS


def _ladder_index(res: object) -> int:
    ladder = list(RENDER_RESOLUTIONS)
    return ladder.index(res) if res in RENDER_RESOLUTIONS else ladder.index(DEFAULT_RESOLUTION)


def derive_render_resolution(outputs: dict | None) -> str:
    """The render canvas = the LARGEST enabled output resolution (every output
    downscales FROM the single capture, never up). 1080p when nothing is enabled.
    Pure — Xvfb/Chromium are sized from this, and each encoder scales down to its
    own resolution."""
    enabled = [
        o.get("resolution")
        for o in (outputs or {}).values()
        if isinstance(o, dict) and o.get("enabled") and o.get("resolution") in RENDER_RESOLUTIONS
    ]
    return max(enabled, key=_ladder_index) if enabled else DEFAULT_RESOLUTION


def enabled_encoder_outputs(outputs: dict | None) -> dict[str, dict]:
    """The enabled ffmpeg-ENCODER outputs (excludes the publisher), preserving the
    config's order. Pure."""
    return {
        name: o
        for name, o in (outputs or {}).items()
        if isinstance(o, dict) and o.get("enabled") and is_encoder_output(name)
    }


def build_capture_fanout_cmd(
    *,
    render_w: int,
    render_h: int,
    fps: int,
    display: str,
    sink: str,
    specs: list[dict],
    grab_queue: int,
    x264_preset: str = "veryfast",
    hls_time: str = "4",
    hls_list_size: str = "6",
    audio_bitrate: str = "128k",
) -> list[str]:
    """ONE ffmpeg: a single x11grab capture of the composited wall, fanned out to
    one HLS encode per spec. With a single spec at the render resolution this is the
    original pipeline (no filter); with >1 it ``split``s the captured video and
    ``scale``s each branch to its own size — capture/composite ONCE, encode N.

    Each ``spec``: ``{w, h, bitrate_kbps, audio, playlist, segments}`` (its own
    downscale target + bitrate + whether to mux the wall audio + its output paths).
    The audio is the SAME PulseAudio sink monitor (the in-browser mix of every
    ``audio:true`` cell); a spec with ``audio:false`` omits the audio track. Pure."""
    cmd = [
        "ffmpeg", "-hide_banner", "-loglevel", "warning", "-nostdin",
        "-thread_queue_size", str(grab_queue),
        "-f", "x11grab", "-draw_mouse", "0", "-framerate", str(fps),
        "-video_size", f"{render_w}x{render_h}", "-i", display,
        "-thread_queue_size", "1024", "-f", "pulse", "-i", f"{sink}.monitor",
    ]
    n = len(specs)
    if n > 1:
        labels = "".join(f"[s{i}]" for i in range(n))
        parts = [f"[0:v]split={n}{labels}"]
        for i, s in enumerate(specs):
            parts.append(f"[s{i}]scale={s['w']}:{s['h']}[v{i}]")
        cmd += ["-filter_complex", ";".join(parts)]
        vmaps = [f"[v{i}]" for i in range(n)]
    else:
        vmaps = ["0:v"]
    for i, s in enumerate(specs):
        kbps = int(s["bitrate_kbps"])
        cmd += ["-map", vmaps[i]]
        if s["audio"]:
            cmd += ["-map", "1:a"]
        cmd += [
            "-c:v", "libx264", "-preset", x264_preset, "-tune", "zerolatency",
            "-pix_fmt", "yuv420p", "-g", str(fps * 2),
            "-b:v", f"{kbps}k", "-maxrate", f"{kbps}k", "-bufsize", f"{kbps * 2}k",
            "-fps_mode", "cfr", "-r", str(fps),
        ]
        if s["audio"]:
            cmd += ["-c:a", "aac", "-b:a", audio_bitrate, "-ar", "44100"]
        cmd += [
            "-f", "hls", "-hls_time", hls_time, "-hls_list_size", hls_list_size,
            "-hls_flags", "delete_segments+append_list+independent_segments",
            "-hls_segment_type", "mpegts", "-hls_segment_filename", s["segments"],
            s["playlist"],
        ]
    return cmd


def _encoder_signature(outputs: dict | None) -> dict:
    return {
        n: (o.get("enabled"), o.get("resolution"), o.get("bitrate_kbps"),
            bool(o.get("audio")), o.get("restart_epoch", 0))
        for n, o in (outputs or {}).items()
        if isinstance(o, dict) and is_encoder_output(n)
    }


def plan_output_restart(old_outputs: dict | None, new_outputs: dict | None) -> dict[str, bool]:
    """The restart-decision matrix when the outputs config changes (avoid thrashing
    the render):
      - ``render_restart``: the derived canvas (max enabled resolution) MOVED →
        restart Xvfb/Chromium (the heavy path); the encoders respawn on the new
        canvas too;
      - ``encoder_restart``: an ffmpeg-encoder output's params changed (a resolution
        change that does NOT move the max, bitrate, audio, enabled, restart_epoch) →
        respawn the capture/encode WITHOUT touching the render.
    Pure — the runtime acts on these flags."""
    render_restart = derive_render_resolution(old_outputs) != derive_render_resolution(new_outputs)
    encoder_restart = render_restart or (
        _encoder_signature(old_outputs) != _encoder_signature(new_outputs)
    )
    return {
        "render_restart": render_restart,
        "encoder_restart": encoder_restart,
    }


def newest_segment_mtime(stream_dir: str | os.PathLike[str]) -> float | None:
    """The mtime of the newest HLS segment in [stream_dir], or None if there is
    no segment yet. Pure read of the filesystem; never raises on a missing dir."""
    d = Path(stream_dir)
    try:
        segs = [p for p in d.iterdir() if p.suffix == SEGMENT_SUFFIX and p.is_file()]
    except (OSError, FileNotFoundError):
        return None
    if not segs:
        return None
    try:
        return max(p.stat().st_mtime for p in segs)
    except OSError:
        return None


def stream_age_seconds(stream_dir: str | os.PathLike[str], now: float) -> float | None:
    """Seconds since the newest segment was written, or None if none exists."""
    mtime = newest_segment_mtime(stream_dir)
    if mtime is None:
        return None
    return now - mtime


def is_stream_healthy(
    stream_dir: str | os.PathLike[str],
    now: float,
    max_age_seconds: float = DEFAULT_MAX_SEGMENT_AGE_S,
) -> bool:
    """True iff the playlist exists AND a fresh segment was written within
    [max_age_seconds] — i.e. ffmpeg is actively advancing the stream. A stale
    or absent stream is unhealthy (the docker HEALTHCHECK + supervisor act on
    this)."""
    if not Path(stream_dir).joinpath(PLAYLIST_NAME).is_file():
        return False
    age = stream_age_seconds(stream_dir, now)
    return age is not None and 0 <= age <= max_age_seconds


DEFAULT_STALE_RESTART_S = 30.0


def stale_stack_restart(
    last_fresh_monotonic: float | None,
    now_monotonic: float,
    healthy: bool,
    threshold_s: float = DEFAULT_STALE_RESTART_S,
) -> bool:
    """Decide whether a WEDGED-but-alive stack should be force-restarted.

    The supervisor restarts a child that *exits*, but ffmpeg/Chromium can HANG —
    still running, no longer advancing the stream (e.g. ffmpeg "frames
    duplicated" + a blocked pulse queue). That is exactly the "please wait
    forever" symptom for a player, and `poll()` never catches it. So once the
    stream has been healthy (``last_fresh_monotonic`` set), if it has now been
    stale (not healthy) longer than ``threshold_s``, the stack is wedged and the
    runtime restarts it. Pure: the runtime supplies the monotonic clock + the
    ``healthy`` flag from :func:`is_stream_healthy`, so it is deterministic +
    unit-testable. Returns False before the stream was ever healthy (don't
    restart during normal startup) and whenever it's currently healthy.
    """
    if healthy or last_fresh_monotonic is None:
        return False
    return (now_monotonic - last_fresh_monotonic) > threshold_s


def next_backoff_seconds(restart_count: int, base: float = 1.0, cap: float = 30.0) -> float:
    """Exponential backoff for a crashing child: base, 2·base, 4·base … capped.
    `restart_count` is how many times this child has already been restarted (0 →
    the first restart waits `base`). Bounded so a hard-failing child retries
    forever at a calm cadence rather than hot-looping."""
    if restart_count < 0:
        restart_count = 0
    delay = base * (2 ** restart_count)
    return min(delay, cap)


class RestartTracker:
    """Tracks a child's restarts within a sliding window to decide when crashes
    are 'rapid' (so the supervisor can escalate — e.g. restart the whole stack
    rather than just the one child). Pure: the caller supplies `now`, so it is
    deterministic + unit-testable."""

    def __init__(self, window_seconds: float = 60.0, max_in_window: int = 5):
        self.window_seconds = window_seconds
        self.max_in_window = max_in_window
        self._stamps: list[float] = []

    def record(self, now: float) -> None:
        self._stamps.append(now)
        self._prune(now)

    def _prune(self, now: float) -> None:
        cutoff = now - self.window_seconds
        self._stamps = [t for t in self._stamps if t >= cutoff]

    def count_in_window(self, now: float) -> int:
        self._prune(now)
        return len(self._stamps)

    def is_crash_looping(self, now: float) -> bool:
        """True iff the child has restarted >= max_in_window times within the
        window — a signal to escalate (restart the foundational stack)."""
        return self.count_in_window(now) >= self.max_in_window


# ---- blank / frozen render detection (the 8-days-of-white lesson) ----
# The stale-restart above catches a stream that STOPS advancing. But Chromium can
# wedge on a WHITE (or frozen) page while ffmpeg happily encodes it — a perfectly
# "fresh" stream of blank frames that went undetected for ~8 days until a viewer
# appeared. So we also sample the LIVE render (a tiny grayscale probe frame) and
# trip when it is effectively UNIFORM (blank/white/black) or FROZEN (unchanging)
# across several samples minutes apart. Multiple consecutive bad samples are
# required so legitimately-dark/static content can't false-positive. Pure: the
# runtime grabs the frame; these decide.

# A grayscale probe frame with variance at/below this is "blank" (a solid fill).
# Deliberately LOW: a real wall (tiles + text + a crawling ticker) has variance far
# above this, so only a near-uniform frame trips — biasing against false positives.
DEFAULT_BLANK_MAX_VARIANCE = 4.0
# Two probe frames whose mean per-pixel absolute difference is at/below this are
# "frozen" (the render is not changing). A live wall differs frame-to-frame by far
# more; ~0 means nothing moved between two samples minutes apart.
DEFAULT_FROZEN_MAX_MEAN_DIFF = 2.0
# Consecutive bad (blank OR frozen) samples before tripping a restart. At the
# runtime's minutes-apart cadence this is several minutes of continuously-bad render.
DEFAULT_BLANK_TRIP_AFTER = 3


def frame_variance(frame: bytes) -> float:
    """Population variance of a grayscale probe frame's pixel bytes. ~0 for a solid
    fill (blank/white/black); large for any real content. Pure. Empty → 0.0."""
    n = len(frame)
    if n == 0:
        return 0.0
    mean = sum(frame) / n
    return sum((b - mean) ** 2 for b in frame) / n


def frame_is_blank(frame: bytes, max_variance: float = DEFAULT_BLANK_MAX_VARIANCE) -> bool:
    """True iff the probe frame is effectively uniform (a solid blank/white/black
    fill) — its variance is at/below ``max_variance``. Pure."""
    return frame_variance(frame) <= max_variance


def frames_are_frozen(
    a: bytes, b: bytes, max_mean_diff: float = DEFAULT_FROZEN_MAX_MEAN_DIFF
) -> bool:
    """True iff two same-size grayscale probe frames are effectively identical (the
    render is frozen) — their mean per-pixel absolute difference is at/below
    ``max_mean_diff``. Different sizes or an empty frame → not comparable → False.
    Pure."""
    if not a or not b or len(a) != len(b):
        return False
    total = sum(abs(x - y) for x, y in zip(a, b))
    return (total / len(a)) <= max_mean_diff


class BlankOutputDetector:
    """Tracks CONSECUTIVE bad (blank OR frozen) render samples so a wedged Chromium
    (a white/static page ffmpeg is happily encoding) is caught even though the stream
    stays 'fresh'. Pure: the runtime feeds a sampled probe frame every interval; a
    restart is signalled only after ``trip_after`` consecutive bad samples (minutes
    apart), so a legitimately-dark/static moment can't false-positive. Any good sample
    resets the streak."""

    def __init__(
        self,
        trip_after: int = DEFAULT_BLANK_TRIP_AFTER,
        max_variance: float = DEFAULT_BLANK_MAX_VARIANCE,
        max_mean_diff: float = DEFAULT_FROZEN_MAX_MEAN_DIFF,
    ) -> None:
        self.trip_after = trip_after
        self.max_variance = max_variance
        self.max_mean_diff = max_mean_diff
        self._bad_streak = 0
        self._prev: bytes | None = None

    def record(self, frame: bytes) -> bool:
        """Feed one sampled probe frame; returns True iff a restart should fire NOW
        (``trip_after`` consecutive bad samples reached). The caller gates WHEN to
        sample (only when live tiles are expected + the stream is advancing)."""
        blank = frame_is_blank(frame, self.max_variance)
        frozen = self._prev is not None and frames_are_frozen(
            self._prev, frame, self.max_mean_diff
        )
        self._prev = frame
        if blank or frozen:
            self._bad_streak += 1
        else:
            self._bad_streak = 0
        return self._bad_streak >= self.trip_after

    def reset(self) -> None:
        """Clear the streak + last frame (after a restart, or when sampling pauses)."""
        self._bad_streak = 0
        self._prev = None
