#!/usr/bin/env python3
"""Unit tests for the renderer's PURE supervision logic (no Chromium/ffmpeg).

Stdlib `unittest` so it runs anywhere with python3 (no pytest/uv needed in the
renderer image's toolchain):  `python3 -m unittest discover -s renderer`.
"""

from __future__ import annotations

import os
import tempfile
import time
import unittest

import supervisor as sup


class StreamFreshness(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()

    def _seg(self, name, mtime=None):
        p = os.path.join(self.tmp, name)
        with open(p, "w") as f:
            f.write("x")
        if mtime is not None:
            os.utime(p, (mtime, mtime))
        return p

    def test_no_segments_returns_none(self):
        self.assertIsNone(sup.newest_segment_mtime(self.tmp))
        self.assertIsNone(sup.stream_age_seconds(self.tmp, time.time()))

    def test_missing_dir_never_raises(self):
        self.assertIsNone(sup.newest_segment_mtime("/no/such/dir"))
        self.assertFalse(sup.is_stream_healthy("/no/such/dir", time.time()))

    def test_newest_segment_wins(self):
        now = 1000.0
        self._seg("seg_00001.ts", now - 30)
        self._seg("seg_00002.ts", now - 5)
        self.assertAlmostEqual(sup.newest_segment_mtime(self.tmp), now - 5, places=3)
        self.assertAlmostEqual(sup.stream_age_seconds(self.tmp, now), 5, places=3)

    def test_non_ts_files_ignored(self):
        now = 1000.0
        self._seg("playlist.m3u8", now - 1)   # not a .ts → ignored for freshness
        self._seg("seg_00001.ts", now - 12)
        self.assertAlmostEqual(sup.stream_age_seconds(self.tmp, now), 12, places=3)

    def test_healthy_requires_playlist_and_fresh_segment(self):
        now = 1000.0
        # fresh segment but NO playlist → unhealthy
        self._seg("seg_00001.ts", now - 2)
        self.assertFalse(sup.is_stream_healthy(self.tmp, now, max_age_seconds=20))
        # add the playlist → healthy
        self._seg("playlist.m3u8", now)
        self.assertTrue(sup.is_stream_healthy(self.tmp, now, max_age_seconds=20))

    def test_stale_segment_is_unhealthy(self):
        now = 1000.0
        self._seg("playlist.m3u8", now - 60)
        self._seg("seg_00001.ts", now - 60)   # 60s old > 20s window
        self.assertFalse(sup.is_stream_healthy(self.tmp, now, max_age_seconds=20))


class WedgeRestart(unittest.TestCase):
    def test_no_restart_before_stream_ever_healthy(self):
        # startup: never healthy yet → don't restart (let it boot)
        self.assertFalse(sup.stale_stack_restart(None, 1000.0, healthy=False, threshold_s=30))

    def test_no_restart_while_healthy(self):
        self.assertFalse(sup.stale_stack_restart(900.0, 1000.0, healthy=True, threshold_s=30))

    def test_no_restart_within_threshold(self):
        # was healthy at t=980, now t=1000 (20s stale) < 30s → tolerate the blip
        self.assertFalse(sup.stale_stack_restart(980.0, 1000.0, healthy=False, threshold_s=30))

    def test_restart_when_stale_past_threshold(self):
        # was healthy at t=960, now t=1000 (40s stale) > 30s + processes alive → wedged
        self.assertTrue(sup.stale_stack_restart(960.0, 1000.0, healthy=False, threshold_s=30))


class Backoff(unittest.TestCase):
    def test_exponential_then_capped(self):
        self.assertEqual(sup.next_backoff_seconds(0, base=1, cap=30), 1)
        self.assertEqual(sup.next_backoff_seconds(1, base=1, cap=30), 2)
        self.assertEqual(sup.next_backoff_seconds(2, base=1, cap=30), 4)
        self.assertEqual(sup.next_backoff_seconds(3, base=1, cap=30), 8)
        self.assertEqual(sup.next_backoff_seconds(10, base=1, cap=30), 30)   # capped
        self.assertEqual(sup.next_backoff_seconds(-5, base=1, cap=30), 1)    # defensive


class Tracker(unittest.TestCase):
    def test_window_prunes_old_restarts(self):
        t = sup.RestartTracker(window_seconds=60, max_in_window=5)
        t.record(0)
        t.record(10)
        self.assertEqual(t.count_in_window(20), 2)
        # 70s later the early stamps fall out of the 60s window
        self.assertEqual(t.count_in_window(75), 0)

    def test_crash_loop_detection(self):
        t = sup.RestartTracker(window_seconds=60, max_in_window=3)
        for i in range(2):
            t.record(float(i))
        self.assertFalse(t.is_crash_looping(2))
        t.record(2)
        self.assertTrue(t.is_crash_looping(2))   # 3 within the window
        # but spread out beyond the window → not looping
        t2 = sup.RestartTracker(window_seconds=10, max_in_window=3)
        t2.record(0); t2.record(20); t2.record(40)
        self.assertFalse(t2.is_crash_looping(40))


class RenderResolution(unittest.TestCase):
    def test_known_resolutions_map_to_dimensions(self):
        self.assertEqual(sup.resolution_to_dimensions("1080p"), (1920, 1080))
        self.assertEqual(sup.resolution_to_dimensions("2160p"), (3840, 2160))
        self.assertEqual(sup.resolution_to_dimensions("1440p"), (2560, 1440))
        self.assertEqual(sup.resolution_to_dimensions("720p"), (1280, 720))

    def test_unknown_resolution_falls_back_to_1080p(self):
        self.assertEqual(sup.normalize_resolution("999p"), "1080p")
        self.assertEqual(sup.normalize_resolution(None), "1080p")
        self.assertEqual(sup.resolution_to_dimensions("nonsense"), (1920, 1080))

    def test_bitrate_scales_with_resolution(self):
        self.assertEqual(sup.resolution_to_bitrate("1080p"), ("8M", "16M"))   # motion headroom
        self.assertEqual(sup.resolution_to_bitrate("2160p"), ("20M", "40M"))  # heavier 4K encode
        self.assertEqual(sup.resolution_to_bitrate("bogus"), ("8M", "16M"))   # fallback → 1080p

    def test_fps_is_sustainable_per_resolution(self):
        # ≤1080p capped at 30; above the ceiling, the sustainable CFR falls
        self.assertEqual(sup.resolution_to_fps("720p"), 30)
        self.assertEqual(sup.resolution_to_fps("1080p"), 30)
        self.assertEqual(sup.resolution_to_fps("1440p"), 20)
        self.assertEqual(sup.resolution_to_fps("2160p"), 10)
        self.assertEqual(sup.resolution_to_fps("bogus"), 30)   # fallback → 1080p/30

    def test_every_resolution_has_a_complete_profile(self):
        # the 8-rung ladder: every name maps to dims + fps + bitrate, all 16:9, even dims
        for name, (w, h) in sup.RENDER_RESOLUTIONS.items():
            self.assertAlmostEqual(w / h, 16 / 9, places=6, msg=name)
            self.assertEqual(w % 2, 0, name)
            self.assertEqual(h % 2, 0, name)
            self.assertIn(name, sup.RENDER_FPS, name)
            self.assertIn(name, sup.RENDER_BITRATES, name)
        self.assertEqual(len(sup.RENDER_RESOLUTIONS), 8)

    def test_grab_queue_is_memory_bounded_at_every_rung(self):
        # The queue depth is bounded by raw-frame MEMORY at every ladder rung, not a
        # single 4K threshold: a deep queue behind a slow encoder would OOM the
        # container (a 4K raw frame is ~33MB; even a mid rung balloons to many GB).
        budget = sup.GRAB_QUEUE_MEM_BUDGET
        for name, (w, h) in sup.RENDER_RESOLUTIONS.items():
            depth = sup.grab_queue_size(w, h)
            self.assertGreaterEqual(depth, 32, name)        # never below the floor
            self.assertLessEqual(depth, 1024, name)         # never above the cap
            self.assertLessEqual(depth * w * h * 4, budget, name)  # worst-case ≤ 1 GiB
        # the intermediate rungs the ladder added (NOT just 4K) are bounded too —
        # 1800p (3200x1800) would have kept the deep 1024 queue under a width-only gate
        self.assertLessEqual(sup.grab_queue_size(3200, 1800), 64)
        self.assertEqual(sup.grab_queue_size(3840, 2160), 32)   # 4K floors out
        # low res keeps a generous multi-second spike buffer
        self.assertGreaterEqual(sup.grab_queue_size(1920, 1080), 128)
        # height defaults to 16:9 from width when omitted (back-compat)
        self.assertEqual(sup.grab_queue_size(1920), sup.grab_queue_size(1920, 1080))


class EncodePipeline(unittest.TestCase):
    """Guard the real-time encode flags (the smoothness contract) in run.ffmpeg_cmd."""

    def _cmd(self):
        import run  # safe to import: module-level is config only, no side effects
        return run.ffmpeg_cmd()

    def _adjacent(self, cmd, flag, value):
        # assert `flag value` appear consecutively in the argv
        for i, tok in enumerate(cmd[:-1]):
            if tok == flag and cmd[i + 1] == value:
                return True
        return False

    def test_realtime_flags_present(self):
        cmd = self._cmd()
        self.assertTrue(self._adjacent(cmd, "-tune", "zerolatency"), "missing -tune zerolatency")
        self.assertTrue(self._adjacent(cmd, "-fps_mode", "cfr"), "missing -fps_mode cfr (CFR)")
        self.assertTrue(self._adjacent(cmd, "-c:v", "libx264"))
        # CFR rate pinned to the configured FPS
        import run
        self.assertTrue(self._adjacent(cmd, "-r", str(run.FPS)))

    def test_grab_queue_matches_resolution(self):
        import run
        cmd = self._cmd()
        self.assertTrue(
            self._adjacent(cmd, "-thread_queue_size", str(sup.grab_queue_size(run.WIDTH, run.HEIGHT)))
        )


if __name__ == "__main__":
    unittest.main()
