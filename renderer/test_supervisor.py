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


def _adjacent(cmd, flag, value):
    """assert `flag value` appear consecutively in the argv"""
    for i, tok in enumerate(cmd[:-1]):
        if tok == flag and cmd[i + 1] == value:
            return True
    return False


# the two ladder outputs used in the fan-out tests (paths are dummies)
def _spec(name, w, h, kbps, audio):
    return {
        "name": name, "w": w, "h": h, "bitrate_kbps": kbps, "audio": audio,
        "playlist": f"/stream/{name}/playlist.m3u8", "segments": f"/stream/{name}/seg_%05d.ts",
    }


class EncodePipeline(unittest.TestCase):
    """Guard the real-time encode flags (the smoothness contract) in the fan-out."""

    def _cmd(self):
        # a single 1080p HLS output — the shipping case (no split, the original
        # pipeline parameterized).
        return sup.build_capture_fanout_cmd(
            render_w=1920, render_h=1080, fps=30, display=":99", sink="mymts",
            specs=[_spec("hls", 1920, 1080, 8000, True)], grab_queue=sup.grab_queue_size(1920, 1080),
        )

    def test_realtime_flags_present(self):
        cmd = self._cmd()
        self.assertTrue(_adjacent(cmd, "-tune", "zerolatency"), "missing -tune zerolatency")
        self.assertTrue(_adjacent(cmd, "-fps_mode", "cfr"), "missing -fps_mode cfr (CFR)")
        self.assertTrue(_adjacent(cmd, "-c:v", "libx264"))
        self.assertTrue(_adjacent(cmd, "-r", "30"))             # CFR rate pinned to the fps
        self.assertNotIn("-filter_complex", cmd)                # single output → no split

    def test_grab_queue_matches_resolution(self):
        cmd = self._cmd()
        self.assertTrue(_adjacent(cmd, "-thread_queue_size", str(sup.grab_queue_size(1920, 1080))))

    def test_single_output_audio_can_be_omitted(self):
        cmd = sup.build_capture_fanout_cmd(
            render_w=1920, render_h=1080, fps=30, display=":99", sink="mymts",
            specs=[_spec("hls", 1920, 1080, 8000, False)], grab_queue=129,
        )
        self.assertNotIn("aac", cmd)                            # audio:false → no audio track


class MultiOutputFanout(unittest.TestCase):
    """Capture-once → encode-N: the derived render resolution + the fan-out cmd +
    the restart-decision matrix."""

    def test_derive_render_resolution_is_max_enabled(self):
        o = {"hls": {"enabled": True, "resolution": "1080p"},
             "mercury": {"enabled": True, "resolution": "720p"}}
        self.assertEqual(sup.derive_render_resolution(o), "1080p")
        # a higher-res output (incl. a non-hls encoder) lifts the canvas
        o2 = {"hls": {"enabled": True, "resolution": "720p"},
              "hls2": {"enabled": True, "resolution": "1440p"}}
        self.assertEqual(sup.derive_render_resolution(o2), "1440p")
        # nothing enabled → 1080p default; disabled outputs don't count
        self.assertEqual(sup.derive_render_resolution({"hls": {"enabled": False, "resolution": "2160p"}}), "1080p")

    def test_one_output_no_split_two_outputs_split_and_scale(self):
        one = sup.build_capture_fanout_cmd(
            render_w=1920, render_h=1080, fps=30, display=":99", sink="mymts",
            specs=[_spec("hls", 1920, 1080, 8000, True)], grab_queue=129,
        )
        self.assertNotIn("-filter_complex", one)
        self.assertEqual(one.count("libx264"), 1)
        # two outputs (e.g. a second hls-like test output) → ONE x11grab, split, two encodes
        two = sup.build_capture_fanout_cmd(
            render_w=1920, render_h=1080, fps=30, display=":99", sink="mymts",
            specs=[_spec("hls", 1920, 1080, 8000, True), _spec("hls2", 1280, 720, 3000, False)],
            grab_queue=129,
        )
        self.assertEqual(two.count("x11grab"), 1)             # captured ONCE (composite once)
        self.assertEqual(two.count("hls"), 2)                # ...fanned out to two HLS muxers
        fc = two[two.index("-filter_complex") + 1]
        self.assertIn("split=2", fc)
        self.assertIn("scale=1280:720", fc)
        self.assertEqual(two.count("libx264"), 2)              # two encodes from one capture
        self.assertIn("/stream/hls2/playlist.m3u8", two)

    def _outputs(self, **over):
        base = {
            "hls": {"enabled": True, "resolution": "1080p", "bitrate_kbps": 8000,
                    "audio": True, "restart_epoch": 0},
            "mercury": {"enabled": False, "resolution": "720p", "bitrate_kbps": 3000,
                        "audio": True, "restart_epoch": 0, "channel_guid": "", "display_name": "X"},
        }
        for k, v in over.items():
            base[k] = {**base[k], **v}
        return base

    def test_restart_matrix_resolution_move_restarts_render(self):
        old = self._outputs()
        new = self._outputs(hls={"resolution": "2160p"})   # moves the max
        p = sup.plan_output_restart(old, new)
        self.assertTrue(p["render_restart"] and p["encoder_restart"])
        self.assertFalse(p["publisher_restart"])

    def test_restart_matrix_bitrate_restarts_encoder_only(self):
        p = sup.plan_output_restart(self._outputs(), self._outputs(hls={"bitrate_kbps": 5000}))
        self.assertFalse(p["render_restart"])
        self.assertTrue(p["encoder_restart"])
        self.assertFalse(p["publisher_restart"])

    def test_restart_matrix_audio_toggle_restarts_encoder_only(self):
        p = sup.plan_output_restart(self._outputs(), self._outputs(hls={"audio": False}))
        self.assertEqual((p["render_restart"], p["encoder_restart"], p["publisher_restart"]), (False, True, False))

    def test_restart_matrix_mercury_change_restarts_publisher_only(self):
        p = sup.plan_output_restart(self._outputs(), self._outputs(mercury={"restart_epoch": 1}))
        self.assertEqual((p["render_restart"], p["encoder_restart"], p["publisher_restart"]), (False, False, True))

    def test_restart_matrix_no_change_is_noop(self):
        p = sup.plan_output_restart(self._outputs(), self._outputs())
        self.assertFalse(any(p.values()))

    def test_encoder_outputs_exclude_the_publisher(self):
        self.assertEqual(list(sup.enabled_encoder_outputs(self._outputs(mercury={"enabled": True}))), ["hls"])
        self.assertTrue(sup.is_publisher_output("mercury"))
        self.assertTrue(sup.is_encoder_output("hls"))


class MercuryStub(unittest.TestCase):
    """The StubMercuryPublisher state machine — and that it NEVER opens a connection."""

    def _boom_probe(self, _url):
        raise AssertionError("the stub opened a network probe — egress leak!")

    def test_disabled_state_no_probe(self):
        import mercury
        p = mercury.StubMercuryPublisher(env={}, log=lambda m: None, probe=self._boom_probe)
        p.configure({"enabled": False})
        st = p.status()
        self.assertEqual(st["state"], "disabled")
        self.assertIsNone(st["checklist"]["tailnet_reachable"])   # never probed

    def test_needs_setup_when_key_missing_no_probe(self):
        import mercury
        # key absent → needs_setup, and the probe is short-circuited (zero egress)
        p = mercury.StubMercuryPublisher(env={}, log=lambda m: None, probe=self._boom_probe)
        p.configure({"enabled": True, "channel_guid": "g"})
        st = p.status()
        self.assertEqual(st["state"], "needs_setup")
        self.assertIn("LiveKit key", st["detail"])
        self.assertFalse(st["checklist"]["key_present"])

    def test_ready_not_wired_when_all_present(self):
        import mercury
        env = {"LIVEKIT_API_KEY": "k", "LIVEKIT_API_SECRET": "s", "LIVEKIT_HOST": "wss://h:7095/x"}
        p = mercury.StubMercuryPublisher(env=env, log=lambda m: None, probe=lambda u: True)
        p.configure({"enabled": True, "channel_guid": "g", "resolution": "720p"})
        st = p.status()
        self.assertEqual(st["state"], "ready_not_wired")
        self.assertTrue(all(st["checklist"][k] for k in ("key_present", "channel_set", "tailnet_reachable")))

    def test_start_stop_restart_are_inert(self):
        import mercury
        logs = []
        p = mercury.StubMercuryPublisher(env={}, log=logs.append, probe=self._boom_probe)
        p.configure({"enabled": True, "channel_guid": "g", "resolution": "720p"})
        p.start()                                    # must not raise, must not connect
        p.stop()
        p.restart()
        self.assertTrue(any("NOT wired" in m for m in logs))


if __name__ == "__main__":
    unittest.main()
