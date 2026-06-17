"""Tests for the desktop launcher's pure parts (tray decision, port, data dir)."""

from __future__ import annotations

import socket
import sys
from pathlib import Path

import launch as L


def test_should_use_tray_matrix():
    # macOS / Windows are desktop -> tray iff pystray imports.
    assert L.should_use_tray("darwin", forced_headless=False, has_display=False, pystray_importable=True)
    assert L.should_use_tray("win32", forced_headless=False, has_display=False, pystray_importable=True)
    assert not L.should_use_tray("darwin", forced_headless=False, has_display=False, pystray_importable=False)
    # Forced headless always wins.
    assert not L.should_use_tray("darwin", forced_headless=True, has_display=True, pystray_importable=True)
    # Linux needs BOTH a display AND pystray.
    assert L.should_use_tray("linux", forced_headless=False, has_display=True, pystray_importable=True)
    assert not L.should_use_tray("linux", forced_headless=False, has_display=False, pystray_importable=True)
    assert not L.should_use_tray("linux", forced_headless=False, has_display=True, pystray_importable=False)


def test_resolve_port_prefers_free_port():
    # An almost-certainly-free high port is returned as-is.
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        free = probe.getsockname()[1]
    # (probe closed) — resolve_port should hand back exactly that port.
    assert L.resolve_port(free) == free


def test_resolve_port_falls_back_when_taken():
    # Hold the preferred port; resolve_port must return a DIFFERENT, valid port.
    with socket.socket() as held:
        held.bind(("127.0.0.1", 0))
        held.listen(1)
        taken = held.getsockname()[1]
        got = L.resolve_port(taken)
        assert got != taken
        assert 1 <= got <= 65535


def test_user_data_dir_is_under_home_and_created(tmp_path, monkeypatch):
    # Point HOME at a temp dir so the test doesn't touch the real data dir.
    monkeypatch.setenv("HOME", str(tmp_path))
    monkeypatch.setenv("XDG_DATA_HOME", str(tmp_path / "xdg"))
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path / "local"))
    monkeypatch.setattr(Path, "home", classmethod(lambda cls: tmp_path))
    d = L.user_data_dir()
    assert d.is_dir()                       # created
    assert "MyMTS" in d.parts               # namespaced
    assert str(d).startswith(str(tmp_path))  # under (fake) home — never the bundle


def test_make_icon_returns_image():
    img = L._make_icon()
    assert img.size == (64, 64)
    assert img.mode == "RGBA"


def test_bundled_web_dir_resolves_repo_web_in_dev():
    # Not frozen -> resolves the repo's web/ (a sibling of helper/, two up from
    # tools/desktop/). It exists in the repo.
    assert not getattr(sys, "frozen", False)
    web = L.bundled_web_dir()
    assert web is not None and (web / "index.html").is_file()
