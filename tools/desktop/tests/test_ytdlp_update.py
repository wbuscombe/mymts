"""Tests for the yt-dlp self-update — pure decision logic + the finder mechanism.

No real network: the orchestrator's `http_get` is injected. The integration test
builds a fake yt-dlp wheel and proves the external copy is preferred over what's
on the (frozen-stand-in) import path — the mechanism the real bundle relies on.
"""

from __future__ import annotations

import hashlib
import io
import json
import sys
import zipfile

import pytest

import ytdlp_update as U


# ── pure logic ──────────────────────────────────────────────────────────────

@pytest.mark.parametrize(
    "url,ok",
    [
        ("https://pypi.org/pypi/yt-dlp/json", True),
        ("https://files.pythonhosted.org/packages/aa/yt_dlp-2026.3.17-py3-none-any.whl", True),
        ("http://pypi.org/pypi/yt-dlp/json", False),            # not https
        ("https://evil.example/yt_dlp.whl", False),            # not official host
        ("https://pypi.org.evil.example/x", False),            # lookalike host
        ("not a url", False),
        ("", False),
    ],
)
def test_is_official_url(url, ok):
    assert U.is_official_url(url) is ok


def test_parse_version_orders_date_based():
    assert U.parse_version("2026.3.17") == (2026, 3, 17)
    assert U.parse_version("2026.10.1") > U.parse_version("2026.3.17")
    assert U.parse_version("2026.3.17") > U.parse_version("2025.12.31")
    assert U.parse_version("1.2.3a") == (1, 2, 3)   # non-numeric degrades, never raises
    assert U.parse_version("") == (0,)


def test_should_check_ttl():
    assert U.should_check(now=1000.0, last_checked=None, ttl_seconds=100) is True
    assert U.should_check(now=1000.0, last_checked=850.0, ttl_seconds=100) is True   # elapsed
    assert U.should_check(now=1000.0, last_checked=950.0, ttl_seconds=100) is False  # not yet


def test_verify_sha256():
    data = b"hello yt-dlp"
    good = hashlib.sha256(data).hexdigest()
    assert U.verify_sha256(data, good) is True
    assert U.verify_sha256(data, good.upper()) is True          # case-insensitive
    assert U.verify_sha256(data, "deadbeef") is False
    assert U.verify_sha256(data, "") is False                   # empty rejected


def _pypi_json(version, url, sha, *, packagetype="bdist_wheel", filename=None):
    filename = filename or f"yt_dlp-{version}-py3-none-any.whl"
    return {
        "info": {"version": version},
        "urls": [
            {"packagetype": "sdist", "filename": f"yt_dlp-{version}.tar.gz",
             "url": "https://files.pythonhosted.org/x.tar.gz", "digests": {"sha256": "x"}},
            {"packagetype": packagetype, "filename": filename, "url": url,
             "digests": {"sha256": sha}},
        ],
    }


def test_parse_release_picks_official_py3_wheel():
    url = "https://files.pythonhosted.org/packages/yt_dlp-2026.5.1-py3-none-any.whl"
    got = U.parse_release(_pypi_json("2026.5.1", url, "abc123"))
    assert got == ("2026.5.1", url, "abc123")


def test_parse_release_rejects_non_official_or_missing():
    # non-official host -> ignored -> None
    assert U.parse_release(_pypi_json("1.0", "https://evil.example/x-py3-none-any.whl", "s")) is None
    # missing sha -> ignored
    assert U.parse_release(_pypi_json("1.0", "https://pypi.org/x-py3-none-any.whl", "")) is None
    # not a wheel -> ignored
    assert U.parse_release(
        _pypi_json("1.0", "https://pypi.org/x.tar.gz", "s", packagetype="sdist",
                   filename="yt_dlp-1.0.tar.gz")) is None
    assert U.parse_release({}) is None


@pytest.mark.parametrize(
    "version,ok",
    [
        ("2026.6.9", True), ("2026.10.1", True), ("1.0+build", True),
        ("../evil", False), ("..", False), ("/abs/evil", False),
        ("a/../../b", False), ("a\\b", False), ("", False), ("x" * 40, False),
    ],
)
def test_is_safe_version(version, ok):
    assert U.is_safe_version(version) is ok


def test_unzip_wheel_rejects_traversal_and_sibling_prefix(tmp_path):
    dest = tmp_path / "1.0"
    # member with .. -> rejected
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("../escape.txt", "x")
    with pytest.raises(ValueError, match="unsafe"):
        U._unzip_wheel(buf.getvalue(), dest)
    # absolute member -> rejected
    buf2 = io.BytesIO()
    with zipfile.ZipFile(buf2, "w") as zf:
        zf.writestr("/etc/evil", "x")
    with pytest.raises(ValueError, match="unsafe"):
        U._unzip_wheel(buf2.getvalue(), dest)


def test_ensure_refuses_unsafe_pypi_version(tmp_path, restore_imports):
    wheel = _fake_wheel("2099.9.9")
    sha = hashlib.sha256(wheel).hexdigest()
    url = "https://files.pythonhosted.org/packages/yt_dlp-x-py3-none-any.whl"

    def fake_get(u):
        # PyPI reports a traversal version string
        return json.dumps(_pypi_json("../../../evil", url, sha)).encode()

    assert U.ensure_current_ytdlp(tmp_path, now=1000.0, http_get=fake_get) is None
    # No install dir was created (the unsafe version was refused before any unzip).
    ytdlp_dir = tmp_path / "ytdlp"
    version_dirs = [p for p in ytdlp_dir.iterdir() if p.is_dir()] if ytdlp_dir.exists() else []
    assert version_dirs == []


def test_select_preferred():
    inst = {"2026.3.17": object(), "2026.5.1": object()}
    assert U.select_preferred("2026.1.1", inst) == "2026.5.1"     # newest beats frozen
    assert U.select_preferred("2026.9.9", inst) is None           # frozen newer -> stick
    assert U.select_preferred(None, inst) == "2026.5.1"           # no frozen -> newest
    assert U.select_preferred("2026.1.1", {}) is None             # nothing installed


# ── integration: download → verify → unzip → prefer (the finder mechanism) ──

def _fake_wheel(version: str) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("yt_dlp/__init__.py", "from .version import __version__\n")
        zf.writestr("yt_dlp/version.py", f'__version__ = "{version}"\n')
    return buf.getvalue()


@pytest.fixture
def restore_imports():
    """Snapshot + restore sys.meta_path and any yt_dlp modules so the integration
    test can't leak its fake yt_dlp into the rest of the suite."""
    meta = list(sys.meta_path)
    saved = {n: m for n, m in sys.modules.items() if n == "yt_dlp" or n.startswith("yt_dlp.")}
    yield
    U._uninstall_finders()
    sys.meta_path[:] = meta
    for n in [n for n in sys.modules if n == "yt_dlp" or n.startswith("yt_dlp.")]:
        del sys.modules[n]
    sys.modules.update(saved)


def test_ensure_prefers_verified_external_wheel(tmp_path, restore_imports):
    version = "2099.1.1"   # guaranteed newer than any real/frozen yt-dlp
    wheel = _fake_wheel(version)
    sha = hashlib.sha256(wheel).hexdigest()
    wheel_url = f"https://files.pythonhosted.org/packages/yt_dlp-{version}-py3-none-any.whl"

    def fake_get(url: str) -> bytes:
        if url == U.PYPI_JSON_URL:
            return json.dumps(_pypi_json(version, wheel_url, sha)).encode()
        if url == wheel_url:
            return wheel
        raise AssertionError(f"unexpected url {url}")

    active = U.ensure_current_ytdlp(tmp_path, now=1000.0, http_get=fake_get)
    assert active == version
    # The external copy is now the one that imports.
    import yt_dlp
    assert yt_dlp.version.__version__ == version
    # And it was unzipped into the user-data dir, not anywhere in the bundle.
    assert (tmp_path / "ytdlp" / version / "yt_dlp" / "__init__.py").is_file()


def test_ensure_rejects_sha_mismatch(tmp_path, restore_imports):
    version = "2099.2.2"
    wheel = _fake_wheel(version)
    wheel_url = f"https://files.pythonhosted.org/packages/yt_dlp-{version}-py3-none-any.whl"

    def fake_get(url: str) -> bytes:
        if url == U.PYPI_JSON_URL:
            return json.dumps(_pypi_json(version, wheel_url, "0" * 64)).encode()  # WRONG sha
        return wheel

    active = U.ensure_current_ytdlp(tmp_path, now=1000.0, http_get=fake_get)
    assert active is None   # rejected -> fall back to frozen/none
    assert not (tmp_path / "ytdlp" / version).exists()


def test_ensure_is_offline_safe(tmp_path, restore_imports):
    def boom(url: str) -> bytes:
        raise OSError("network down")

    # Must not raise; returns None (frozen copy stays in effect).
    assert U.ensure_current_ytdlp(tmp_path, now=1000.0, http_get=boom) is None


def test_ensure_throttles_by_ttl(tmp_path, restore_imports):
    calls = {"n": 0}

    def counting_get(url: str) -> bytes:
        calls["n"] += 1
        raise OSError("offline")  # forces no install; we only count the check

    U.ensure_current_ytdlp(tmp_path, now=1000.0, ttl_seconds=100, http_get=counting_get)
    first = calls["n"]
    # within TTL -> no second network check
    U.ensure_current_ytdlp(tmp_path, now=1050.0, ttl_seconds=100, http_get=counting_get)
    assert calls["n"] == first
    # past TTL -> checks again
    U.ensure_current_ytdlp(tmp_path, now=2000.0, ttl_seconds=100, http_get=counting_get)
    assert calls["n"] > first
