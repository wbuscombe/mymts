"""Pins where the helper puts its SQLite DB on first run — the resolution behind
the clean-clone story.

`_default_data_dir` prefers a writable `/data` (the container's mounted state
volume); without one it falls back to a writable per-user dir so a local run still
boots with zero config. The clean-clone compose crash-loop was exactly this: a
`/data` that existed in the image but was NOT writable (read-only rootfs, no
volume) + a read-only home, so neither path was writable. The fix mounts a
writable `/data` volume; these tests pin the resolution the fix relies on.
"""

from __future__ import annotations

import os

from mymts_helper import config


def test_default_data_dir_prefers_writable_data(monkeypatch) -> None:
    # Simulate the container's mounted, writable /data volume.
    real_isdir, real_access = os.path.isdir, os.access
    monkeypatch.setattr(os.path, "isdir", lambda p: p == "/data" or real_isdir(p))
    monkeypatch.setattr(os, "access", lambda p, m: p == "/data" or real_access(p, m))
    assert config._default_data_dir() == "/data"


def test_default_data_dir_falls_back_when_data_unwritable(monkeypatch, tmp_path) -> None:
    # /data present but NOT writable (the read-only-rootfs / no-volume case) →
    # must fall back to a writable per-user dir, not return an unwritable /data.
    real_access = os.access
    monkeypatch.setattr(os.path, "isdir", lambda p: True if p == "/data" else os.path.isdir(p))
    monkeypatch.setattr(os, "access", lambda p, m: False if p == "/data" else real_access(p, m))
    monkeypatch.setenv("XDG_DATA_HOME", str(tmp_path))
    assert config._default_data_dir() == str(tmp_path / "mymts-helper")


def test_default_data_dir_falls_back_when_data_absent(monkeypatch, tmp_path) -> None:
    # No /data at all (a bare local run) → per-user fallback.
    monkeypatch.setattr(os.path, "isdir", lambda p: False if p == "/data" else os.path.isdir(p))
    monkeypatch.setenv("XDG_DATA_HOME", str(tmp_path))
    assert config._default_data_dir() == str(tmp_path / "mymts-helper")
