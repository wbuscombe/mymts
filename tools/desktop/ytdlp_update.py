"""Keep yt-dlp current in the frozen desktop bundle.

A frozen yt-dlp can't self-update, so the YouTube channels rot as YouTube changes
extraction. This module closes that: on launch (throttled by a TTL) it fetches
the latest yt-dlp **from PyPI's official hosts over HTTPS**, verifies the wheel's
**SHA256 against PyPI's published digest**, unzips it into the user-data dir, and
**prefers it over the frozen copy** — falling back to the frozen yt-dlp whenever
the network is unavailable or anything fails. The direct-HLS channels never need
this; only the YouTube-kind channels do.

Security posture (the spec the adversarial review checks):
  - Fetch ONLY from PyPI's official hosts (pypi.org for metadata,
    files.pythonhosted.org for the wheel), HTTPS only — host-allowlisted.
  - Verify the downloaded bytes' SHA256 against the digest PyPI publishes in its
    JSON API (the integrity root is PyPI + TLS). A mismatch is rejected.
  - Install a Python WHEEL of the SAME trusted dependency we already bundle — a
    pure-python `py3-none-any` wheel (no native binary is downloaded or executed).
    yt-dlp is imported as a library, exactly as the frozen copy is.
  - Offline-safe and launch-safe: every failure path leaves the frozen yt-dlp in
    place; a freshly-installed copy that won't import is discarded.

The network/sys-effect orchestration is `ensure_current_ytdlp`; the decision
logic (URL allowlist, wheel pick, version compare, TTL, SHA256, prefer/fallback)
is pure and unit-tested.
"""

from __future__ import annotations

import hashlib
import hmac
import importlib
import importlib.abc
import importlib.machinery
import io
import json
import logging
import re
import shutil
import sys
import time
import zipfile
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import Request, urlopen

log = logging.getLogger("mymts.ytdlp_update")

# Official PyPI hosts — the ONLY hosts we will fetch from.
OFFICIAL_HOSTS = frozenset({"pypi.org", "files.pythonhosted.org"})
PYPI_JSON_URL = "https://pypi.org/pypi/yt-dlp/json"
DEFAULT_TTL_SECONDS = 24 * 60 * 60  # check at most once a day
_HTTP_TIMEOUT = 20
_MAX_WHEEL_BYTES = 64 * 1024 * 1024  # generous cap; the yt-dlp wheel is a few MB
_UA = "mymts-desktop/0.0 (+yt-dlp self-update)"


# ── pure decision logic (unit-tested, no I/O) ───────────────────────────────

def is_official_url(url: str) -> bool:
    """True iff `url` is HTTPS and its host is an official PyPI host."""
    try:
        p = urlparse(url)
    except (ValueError, TypeError):
        return False
    return p.scheme == "https" and (p.hostname or "").lower() in OFFICIAL_HOSTS


def parse_version(s: str) -> tuple[int, ...]:
    """yt-dlp uses date-based versions (e.g. '2026.3.17'). Parse to a tuple for
    ordering; non-numeric components degrade to 0 (never raises)."""
    out: list[int] = []
    for part in str(s).split("."):
        digits = "".join(ch for ch in part if ch.isdigit())
        out.append(int(digits) if digits else 0)
    return tuple(out)


def parse_release(pypi_json: dict) -> tuple[str, str, str] | None:
    """From the PyPI JSON for yt-dlp, return (version, wheel_url, sha256) for the
    latest version's pure-python wheel, or None if it can't be found.

    Only a `py3-none-any` wheel from an OFFICIAL host with a published sha256 is
    accepted (an unverifiable or non-official artifact is ignored)."""
    try:
        version = pypi_json["info"]["version"]
        files = pypi_json["urls"]
    except (KeyError, TypeError):
        return None
    for f in files:
        try:
            if f.get("packagetype") != "bdist_wheel":
                continue
            fn = f.get("filename", "")
            if not fn.endswith("-py3-none-any.whl"):
                continue
            url = f.get("url", "")
            sha = (f.get("digests") or {}).get("sha256", "")
            if url and sha and is_official_url(url):
                return (str(version), str(url), str(sha))
        except (AttributeError, TypeError):
            continue
    return None


def should_check(now: float, last_checked: float | None, ttl_seconds: float) -> bool:
    """True iff the TTL since the last check has elapsed (or never checked)."""
    if last_checked is None:
        return True
    return (now - last_checked) >= ttl_seconds


# A version string we'll trust as a filesystem path component. yt-dlp versions
# are date-based (e.g. 2026.6.9); reject anything with a separator / traversal.
_SAFE_VERSION_RE = re.compile(r"^[0-9][0-9A-Za-z.+_-]{0,31}$")


def is_safe_version(version: str) -> bool:
    """True iff `version` is safe to use as a single path component (no slash,
    backslash, '..', or os-sep). PyPI metadata is attacker-influenced if PyPI is
    compromised, so the version (used to build the install dir) is validated."""
    if not isinstance(version, str) or not _SAFE_VERSION_RE.match(version):
        return False
    return ".." not in version and "/" not in version and "\\" not in version


def verify_sha256(data: bytes, expected_hex: str) -> bool:
    """Compare the data's SHA256 against the expected hex (constant-time)."""
    if not expected_hex:
        return False
    actual = hashlib.sha256(data).hexdigest()
    return hmac.compare_digest(actual.lower(), expected_hex.strip().lower())


def select_preferred(frozen_version: str | None, installed: dict[str, Path]) -> str | None:
    """Pick the newest INSTALLED version that beats the frozen one, else None
    (meaning: stick with the frozen yt-dlp). `installed` maps version → dir."""
    if not installed:
        return None
    newest = max(installed, key=parse_version)
    if frozen_version is None:
        return newest
    return newest if parse_version(newest) > parse_version(frozen_version) else None


# ── external-package finder (precedence over PyInstaller's frozen copy) ──────

class _ExternalPackageFinder(importlib.abc.MetaPathFinder):
    """Resolve one top-level package (and its submodules) from an external dir,
    inserted at sys.meta_path[0] so it OUTRANKS PyInstaller's FrozenImporter.
    Lazy submodule imports follow the package's own __path__ (the external dir)."""

    def __init__(self, root_pkg: str, ext_dir: str) -> None:
        self.root = root_pkg
        self.ext_dir = ext_dir

    def find_spec(self, fullname, path=None, target=None):  # noqa: ARG002
        if fullname == self.root:
            return importlib.machinery.PathFinder.find_spec(fullname, [self.ext_dir])
        if fullname.startswith(self.root + "."):
            # Submodule: `path` derives from the external package __path__.
            return importlib.machinery.PathFinder.find_spec(fullname, path)
        return None


def _install_finder(ext_dir: str) -> _ExternalPackageFinder:
    _uninstall_finders()  # never stack two
    finder = _ExternalPackageFinder("yt_dlp", ext_dir)
    sys.meta_path.insert(0, finder)
    return finder


def _uninstall_finders() -> None:
    sys.meta_path[:] = [
        m for m in sys.meta_path if not isinstance(m, _ExternalPackageFinder)
    ]
    for name in [n for n in sys.modules if n == "yt_dlp" or n.startswith("yt_dlp.")]:
        del sys.modules[name]


# ── I/O helpers ─────────────────────────────────────────────────────────────

def _http_get(url: str) -> bytes:
    """GET an OFFICIAL https URL, bounded. Raises on a non-official host."""
    if not is_official_url(url):
        raise ValueError(f"refusing non-official url: {urlparse(url).hostname!r}")
    req = Request(url, headers={"User-Agent": _UA})
    with urlopen(req, timeout=_HTTP_TIMEOUT) as r:  # noqa: S310 — host-allowlisted https above
        return r.read(_MAX_WHEEL_BYTES + 1)


def _installed_versions(state_dir: Path) -> dict[str, Path]:
    out: dict[str, Path] = {}
    if not state_dir.is_dir():
        return out
    for child in state_dir.iterdir():
        if child.is_dir() and (child / "yt_dlp" / "__init__.py").is_file():
            out[child.name] = child
    return out


def _frozen_ytdlp_version() -> str | None:
    try:
        import yt_dlp  # the bundled copy (finder not yet installed)

        return getattr(getattr(yt_dlp, "version", None), "__version__", None)
    except Exception:  # noqa: BLE001
        return None


def _unzip_wheel(data: bytes, dest: Path) -> None:
    """Extract a wheel (zip) into `dest`. Guards against path traversal — every
    member must land AT or UNDER `dest` (anchored, so a sibling-prefix dir like
    `<dest>-evil` can't pass)."""
    dest.mkdir(parents=True, exist_ok=True)
    base = dest.resolve()
    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        for member in zf.namelist():
            target = (dest / member).resolve()
            if target != base and base not in target.parents:
                raise ValueError(f"unsafe wheel path: {member}")
        zf.extractall(dest)


def _read_state(state_dir: Path) -> dict:
    try:
        return json.loads((state_dir / "state.json").read_text())
    except (OSError, ValueError):
        return {}


def _write_state(state_dir: Path, data: dict) -> None:
    try:
        state_dir.mkdir(parents=True, exist_ok=True)
        (state_dir / "state.json").write_text(json.dumps(data))
    except OSError:
        pass


def _validate_import() -> bool:
    """Confirm the (now finder-preferred) yt_dlp actually imports — so a bad
    external copy can never break launch."""
    _uninstall_modules_only()
    try:
        import yt_dlp

        return bool(getattr(getattr(yt_dlp, "version", None), "__version__", None))
    except Exception:  # noqa: BLE001
        return False


def _uninstall_modules_only() -> None:
    for name in [n for n in sys.modules if n == "yt_dlp" or n.startswith("yt_dlp.")]:
        del sys.modules[name]


# ── orchestrator ────────────────────────────────────────────────────────────

def ensure_current_ytdlp(
    data_dir: Path,
    *,
    ttl_seconds: float = DEFAULT_TTL_SECONDS,
    now: float | None = None,
    http_get=None,
) -> str | None:
    """Prefer a current yt-dlp over the frozen copy. Returns the version now in
    effect (or None = using the frozen copy). NEVER raises — every failure falls
    back to the frozen yt-dlp. `http_get`/`now` are injectable for tests."""
    now = time.time() if now is None else now
    http_get = http_get or _http_get
    state_dir = Path(data_dir) / "ytdlp"
    frozen_version = _frozen_ytdlp_version()

    def _prefer(version: str, ext_root: Path) -> str | None:
        # ext_root contains yt_dlp/; install the finder and validate it imports.
        _install_finder(str(ext_root))
        if _validate_import():
            log.info("ytdlp_external_active", extra={"version": version})
            return version
        log.warning("ytdlp_external_bad_import", extra={"version": version})
        _uninstall_finders()  # discard a copy that won't import -> frozen wins
        # Self-clean: remove the broken install so we don't re-validate /
        # re-download the same incompatible copy on every launch.
        shutil.rmtree(ext_root, ignore_errors=True)
        return None

    try:
        installed = _installed_versions(state_dir)
        # Even offline, prefer the newest already-installed copy that beats frozen.
        active: str | None = None
        best = select_preferred(frozen_version, installed)
        if best is not None:
            active = _prefer(best, installed[best])

        # Throttled online check for something newer.
        st = _read_state(state_dir)
        if should_check(now, st.get("last_checked"), ttl_seconds):
            st["last_checked"] = now
            try:
                meta = json.loads(http_get(PYPI_JSON_URL).decode("utf-8"))
                rel = parse_release(meta)
            except Exception as e:  # noqa: BLE001 — offline / parse error -> keep frozen/installed
                log.info("ytdlp_check_skipped", extra={"reason": str(e)[:120]})
                rel = None
            if rel is not None and not is_safe_version(rel[0]):
                log.warning("ytdlp_unsafe_version", extra={"version": rel[0][:40]})
                rel = None  # refuse a version we won't use as a path component
            if rel is not None:
                version, url, sha = rel
                current_best = active or frozen_version
                if current_best is None or parse_version(version) > parse_version(current_best):
                    try:
                        data = http_get(url)
                        if len(data) > _MAX_WHEEL_BYTES:
                            raise ValueError("wheel too large")
                        if not verify_sha256(data, sha):
                            raise ValueError("sha256 mismatch — rejecting wheel")
                        dest = state_dir / version
                        _unzip_wheel(data, dest)
                        promoted = _prefer(version, dest)
                        if promoted is not None:
                            active = promoted
                    except Exception as e:  # noqa: BLE001 — download/verify failed -> keep current
                        log.warning("ytdlp_update_failed", extra={"reason": str(e)[:120]})
            _write_state(state_dir, st)
        return active
    except Exception as e:  # noqa: BLE001 — belt-and-braces: never break launch
        log.warning("ytdlp_ensure_unexpected", extra={"reason": str(e)[:120]})
        _uninstall_finders()
        return None
