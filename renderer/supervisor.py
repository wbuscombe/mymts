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
RENDER_RESOLUTIONS = {"1080p": (1920, 1080), "2160p": (3840, 2160)}
# resolution → (video bitrate, vbv bufsize). bufsize ~= 2x bitrate so a 4K keyframe
# isn't VBV-clipped. 1080p keeps today's 6M/12M exactly.
RENDER_BITRATES = {"1080p": ("6M", "12M"), "2160p": ("16M", "32M")}


def normalize_resolution(res: object) -> str:
    """Coerce a resolution name to a known key (unknown/None → the 1080p default).
    Pure — the renderer reads the config value through this so a typo/old field
    never picks an invalid canvas."""
    return res if res in RENDER_RESOLUTIONS else DEFAULT_RESOLUTION


def resolution_to_dimensions(res: object) -> tuple[int, int]:
    """(width, height) for a resolution name; unknown → 1080p. Pure."""
    return RENDER_RESOLUTIONS[normalize_resolution(res)]


def resolution_to_bitrate(res: object) -> tuple[str, str]:
    """(video_bitrate, bufsize) for a resolution name; unknown → 1080p. Pure."""
    return RENDER_BITRATES[normalize_resolution(res)]


def grab_queue_size(width: int) -> int:
    """The x11grab INPUT queue depth (frames). A raw 4K frame is ~33MB, so the
    1080p default of 1024 would let the queue balloon to many GB if the software
    encoder falls behind (4K on a CPU-only encoder is heavy) — which OOM-kills
    ffmpeg under the container mem_limit. Bound it hard at 4K so a slow encoder
    DROPS frames (bounded memory, the stream still advances) instead of crashing.
    At 1080p the encoder keeps up easily, so the deep queue (drop-proof under load
    spikes) is kept. Pure — unit-tested."""
    return 32 if width >= 3840 else 1024


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
