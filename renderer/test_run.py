"""Tests for run.py's config-read helpers — the 2026-07-14 encoder-churn fix.

The renderer polled the helper for the `outputs` config every ~12s and respawned
the encoders when it "changed". The old `read_outputs()` returned a single-HLS
FALLBACK on any failed read, which DIFFERS from the live config — so under NAS load
(the 1-CPU helper answering slowly) failed reads flapped the encoder every poll. The
fix splits the read: `fetch_outputs()` returns None on a blip (the loop then leaves
the pipeline untouched), and the divergent fallback is confined to the startup path.
"""

from __future__ import annotations

import unittest

import run


class FetchOutputs(unittest.TestCase):
    def setUp(self) -> None:
        self._orig = run.get_json

    def tearDown(self) -> None:
        run.get_json = self._orig

    def _stub(self, value) -> None:
        run.get_json = lambda url, *a, **k: value

    def test_fetch_outputs_none_on_unreachable_helper(self):
        # A blip → None, so the poll loop keeps the running pipeline (no respawn).
        self._stub(None)
        self.assertIsNone(run.fetch_outputs())

    def test_fetch_outputs_none_on_missing_or_empty_outputs_block(self):
        self._stub({"grid": {"rows": 2}})       # no 'outputs' key
        self.assertIsNone(run.fetch_outputs())
        self._stub({"outputs": {}})             # present but empty
        self.assertIsNone(run.fetch_outputs())

    def test_fetch_outputs_returns_live_outputs_verbatim(self):
        live = {"hls": {"enabled": True, "bitrate_kbps": 24000, "restart_epoch": 3}}
        self._stub({"outputs": live})
        self.assertEqual(run.fetch_outputs(), live)

    def test_read_outputs_falls_back_to_single_hls_only_at_startup(self):
        # The startup path still needs *something* to size the canvas before the
        # helper answers — but this divergent fallback is now used ONLY here, never
        # fed to the poll loop's change-detection.
        self._stub(None)
        out = run.read_outputs()
        self.assertEqual(list(out), ["hls"])
        self.assertTrue(out["hls"]["enabled"])

    def test_read_outputs_prefers_live_over_fallback(self):
        self._stub({"outputs": {"hls": {"enabled": True, "bitrate_kbps": 24000}}})
        self.assertEqual(run.read_outputs()["hls"]["bitrate_kbps"], 24000)   # live, not the 8000 fallback


if __name__ == "__main__":
    unittest.main()
