"""/health/deep: a health signal that asserts the system actually works (MYMTS-025).

`/health` (health.py) is a liveness snapshot: `ok` and `ready` are constants and
`build_sha` is whatever the environment says. Its shape is a consumer contract, so
it is left exactly as it is. This module backs a separate route that evaluates
every subsystem a working wall depends on, from real state:

  database    the SQLite file opens read-only and answers a trivial read
  ingest      the newest successful feed fetch is recent enough
  channels    at least MIN_LIVE_CHANNELS enabled channels are live
  stream      the renderer's HLS playlist exists, its newest segment is fresh,
              and its media sequence keeps advancing
  web_assets  the served web tree is byte-identical to the tree this helper
              build was made for (web_manifest.json, shipped inside the package)

Nothing here reads BUILD_SHA. The build identity it reports is a digest of the
package files the running process started from.

Regenerate the expected-asset manifest whenever web/ changes (a test fails until
you do):

    cd helper && uv run python -m mymts_helper.deep_health web-manifest ../web \
        > src/mymts_helper/web_manifest.json
"""

from __future__ import annotations

import hashlib
import json
import re
import sqlite3
import sys
import threading
import time
from collections.abc import Callable
from datetime import UTC, datetime
from importlib.resources import files
from pathlib import Path
from typing import Any

DEEP_HEALTH_PATH = "/health/deep"
DEEP_HEALTH_SCHEMA_VERSION = 1

# The order checks run in and are reported in.
SUBSYSTEMS = ("database", "ingest", "channels", "stream", "web_assets")

# Ingest is stale when the newest successful fetch across enabled sources is older
# than this many poll intervals, and never sooner than the floor (the floor equals
# /health's 15-minute stale-source threshold).
INGEST_MAX_AGE_POLL_INTERVALS = 3
INGEST_MAX_AGE_FLOOR_SECONDS = 15 * 60

# The wall shows nothing with fewer live channels than this.
MIN_LIVE_CHANNELS = 1

# The renderer's own HEALTHCHECK freshness window
# (renderer/supervisor.py DEFAULT_MAX_SEGMENT_AGE_S).
STREAM_MAX_SEGMENT_AGE_SECONDS = 20.0
# A media sequence that has not moved for this long is not advancing, even if the
# segment files keep being touched: 15 segments at the renderer's default 4 s.
STREAM_MAX_SEQUENCE_STALL_SECONDS = 60.0
# How much of the playlist is read looking for the media-sequence tag.
STREAM_PLAYLIST_MAX_BYTES = 64 * 1024

# How long a read waits on a locked database before the check fails.
DATABASE_TIMEOUT_SECONDS = 5.0

# How many differing paths the web_assets check lists per category.
WEB_ASSET_DIFF_LIST_LIMIT = 20

STREAM_PLAYLIST_NAME = "playlist.m3u8"
STREAM_SEGMENT_GLOB = "seg_*.ts"
WEB_MANIFEST_NAME = "web_manifest.json"
# Left out of the served-tree digest: exactly what deploy-helper.sh's rsync of web/
# excludes, so a clean deploy digests the same as the repository tree.
WEB_DIGEST_EXCLUDED_NAMES = frozenset({".DS_Store", "__pycache__"})
CODE_DIGEST_EXCLUDED_SUFFIXES = (".pyc", ".pyo")

_HASH_CHUNK_BYTES = 1 << 20
_MEDIA_SEQUENCE_RE = re.compile(r"^#EXT-X-MEDIA-SEQUENCE:(\d+)\s*$", re.MULTILINE)


def ingest_max_age_seconds(poll_interval_seconds: int) -> int:
    return max(INGEST_MAX_AGE_FLOOR_SECONDS, INGEST_MAX_AGE_POLL_INTERVALS * poll_interval_seconds)


def _sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(_HASH_CHUNK_BYTES), b""):
            h.update(chunk)
    return h.hexdigest()


def _combine(found: dict[str, str]) -> str:
    lines = "".join(f"{digest}  {rel}\n" for rel, digest in sorted(found.items()))
    return hashlib.sha256(lines.encode("utf-8")).hexdigest()


def tree_digest(root: Path) -> dict[str, Any]:
    """sha256 of every file under `root`, keyed by POSIX relative path, plus one
    digest over the sorted list. A symlink counts by its target text and is never
    followed, so it cannot pull outside content into the identity."""
    root = Path(root)
    found: dict[str, str] = {}

    def walk(directory: Path) -> None:
        for entry in sorted(directory.iterdir()):
            if entry.name in WEB_DIGEST_EXCLUDED_NAMES:
                continue
            rel = entry.relative_to(root).as_posix()
            if entry.is_symlink():
                found[rel] = "symlink:" + str(entry.readlink())
            elif entry.is_dir():
                walk(entry)
            elif entry.is_file():
                found[rel] = _sha256_file(entry)

    walk(root)
    return {"digest": _combine(found), "files": dict(sorted(found.items()))}


def code_digest(package_dir: Path | None = None) -> str:
    """Digest of the helper package's files (sources, migrations, seeds, manifest)."""
    base = Path(package_dir) if package_dir is not None else Path(__file__).resolve().parent
    found: dict[str, str] = {}
    for path in sorted(base.rglob("*")):
        if "__pycache__" in path.parts or path.suffix in CODE_DIGEST_EXCLUDED_SUFFIXES:
            continue
        if path.is_file() and not path.is_symlink():
            found[path.relative_to(base).as_posix()] = _sha256_file(path)
    return _combine(found)


def render_web_manifest(tree: dict[str, Any]) -> str:
    """Serialise a tree_digest result. Files are written as sha256sum-style
    `<sha256>  <path>` lines rather than a {path: hash} object: a hash as the value
    of a key such as "js/api.mjs" reads as an API key to secret scanners."""
    lines = [f"{digest}  {rel}" for rel, digest in sorted(tree["files"].items())]
    return json.dumps({"digest": tree["digest"], "files": lines}, indent=2) + "\n"


def parse_web_manifest(raw: str) -> dict[str, Any]:
    data = json.loads(raw)
    if not (
        isinstance(data, dict)
        and isinstance(data.get("digest"), str)
        and isinstance(data.get("files"), list)
    ):
        raise ValueError(f"{WEB_MANIFEST_NAME} is not a {{digest, files}} object")
    found: dict[str, str] = {}
    for line in data["files"]:
        digest, sep, rel = str(line).partition("  ")
        if not sep or not digest or not rel:
            raise ValueError(f"malformed {WEB_MANIFEST_NAME} line: {line!r}")
        found[rel] = digest
    return {"digest": data["digest"], "files": dict(sorted(found.items()))}


def load_web_manifest() -> dict[str, Any]:
    raw = files("mymts_helper").joinpath(WEB_MANIFEST_NAME).read_text(encoding="utf-8")
    return parse_web_manifest(raw)


def _ok(detail: str, **data: Any) -> dict[str, Any]:
    return {"ok": True, "detail": detail, **data}


def _fail(detail: str, **data: Any) -> dict[str, Any]:
    return {"ok": False, "detail": detail, **data}


def _iso(epoch: float) -> str:
    return datetime.fromtimestamp(epoch, UTC).strftime("%Y-%m-%dT%H:%M:%S.000Z")


class DeepHealth:
    """Evaluates every subsystem on each call. Holds only the stream tracker's last
    seen media sequence, which is what lets it tell "not advancing" from "fresh"."""

    def __init__(
        self,
        *,
        db_path: Path,
        web_dir: str | Path | None,
        stream_dir: str | Path | None,
        feed_poll_interval_seconds: int,
        web_manifest: dict[str, Any] | None = None,
        clock: Callable[[], float] = time.time,
        monotonic: Callable[[], float] = time.monotonic,
    ) -> None:
        self.db_path = Path(db_path)
        self.web_dir = Path(web_dir) if web_dir else None
        self.stream_dir = Path(stream_dir) if stream_dir else None
        self.ingest_max_age = ingest_max_age_seconds(feed_poll_interval_seconds)
        self._clock = clock
        self._monotonic = monotonic
        # Computed once at startup: files edited on disk afterwards are not the
        # code this process is running.
        self.code_digest = code_digest()
        self._manifest_error: str | None = None
        if web_manifest is None:
            try:
                web_manifest = load_web_manifest()
            except (OSError, ValueError) as e:
                self._manifest_error = f"{type(e).__name__}: {e}"
        self._web_manifest = web_manifest
        self._lock = threading.Lock()
        self._last_sequence: int | None = None
        self._sequence_changed_at = 0.0

    def evaluate(self) -> dict[str, Any]:
        checks: dict[str, dict[str, Any]] = {"database": self._check_database()}
        if checks["database"]["ok"]:
            checks["ingest"] = self._with_db(self._check_ingest)
            checks["channels"] = self._with_db(self._check_channels)
        else:
            checks["ingest"] = _fail("not evaluated: the database check failed")
            checks["channels"] = _fail("not evaluated: the database check failed")
        checks["stream"] = self._check_stream()
        checks["web_assets"] = self._check_web_assets()
        failed = [name for name in SUBSYSTEMS if not checks[name]["ok"]]
        return {
            "schema_version": DEEP_HEALTH_SCHEMA_VERSION,
            "healthy": not failed,
            "failed": failed,
            "checks": {name: checks[name] for name in SUBSYSTEMS},
            "build": {
                "code_digest": self.code_digest,
                "web_manifest_digest": (self._web_manifest or {}).get("digest"),
            },
            "checked_at": _iso(self._clock()),
        }

    # ---- database-backed checks ----

    def _connect(self) -> sqlite3.Connection:
        # mode=ro: a missing database is an error here, never silently created.
        uri = f"{self.db_path.resolve().as_uri()}?mode=ro"
        conn = sqlite3.connect(uri, uri=True, timeout=DATABASE_TIMEOUT_SECONDS)
        conn.row_factory = sqlite3.Row
        return conn

    def _with_db(
        self, check: Callable[[sqlite3.Connection], dict[str, Any]]
    ) -> dict[str, Any]:
        try:
            conn = self._connect()
            try:
                return check(conn)
            finally:
                conn.close()
        except sqlite3.Error as e:
            return _fail(f"{type(e).__name__}: {e}")

    def _check_database(self) -> dict[str, Any]:
        def read(conn: sqlite3.Connection) -> dict[str, Any]:
            row = conn.execute("SELECT value FROM meta WHERE key='schema_version'").fetchone()
            if row is None:
                return _fail("meta.schema_version is missing: the database was never migrated")
            try:
                version = int(row["value"])
            except (TypeError, ValueError):
                return _fail(f"meta.schema_version is not an integer: {row['value']!r}")
            return _ok("readable", schema_version=version)

        return self._with_db(read)

    def _check_ingest(self, conn: sqlite3.Connection) -> dict[str, Any]:
        row = conn.execute(
            "SELECT COUNT(*) AS n, MAX(last_success_at) AS newest FROM sources WHERE enabled=1"
        ).fetchone()
        data: dict[str, Any] = {
            "enabled_sources": int(row["n"]),
            "newest_success_at": row["newest"],
            "max_age_seconds": self.ingest_max_age,
        }
        if data["enabled_sources"] == 0:
            return _fail("no enabled feed sources", **data)
        if row["newest"] is None:
            return _fail("no enabled source has ever fetched successfully", **data)
        try:
            newest = datetime.fromisoformat(row["newest"])
        except ValueError:
            return _fail(f"unparseable last_success_at {row['newest']!r}", **data)
        if newest.tzinfo is None:
            newest = newest.replace(tzinfo=UTC)
        age = self._clock() - newest.timestamp()
        data["age_seconds"] = round(age, 1)
        if age > self.ingest_max_age:
            return _fail(
                f"newest successful fetch is {age:.0f}s old (limit {self.ingest_max_age}s)",
                **data,
            )
        return _ok("fresh", **data)

    def _check_channels(self, conn: sqlite3.Connection) -> dict[str, Any]:
        row = conn.execute(
            "SELECT COUNT(*) AS enabled, "
            "SUM(CASE WHEN status='live' THEN 1 ELSE 0 END) AS live "
            "FROM channels WHERE enabled=1"
        ).fetchone()
        live = int(row["live"] or 0)
        data = {
            "enabled_count": int(row["enabled"]),
            "live_count": live,
            "min_live": MIN_LIVE_CHANNELS,
        }
        if live < MIN_LIVE_CHANNELS:
            return _fail(f"{live} live channels (need at least {MIN_LIVE_CHANNELS})", **data)
        return _ok(f"{live} live channels", **data)

    # ---- renderer stream ----

    def _check_stream(self) -> dict[str, Any]:
        if self.stream_dir is None:
            return _fail("stream_dir is not configured: no renderer stream to assert")
        playlist = self.stream_dir / STREAM_PLAYLIST_NAME
        if playlist.is_symlink() or not playlist.is_file():
            return _fail("playlist absent: the renderer is not producing a stream")
        try:
            with playlist.open("rb") as f:
                text = f.read(STREAM_PLAYLIST_MAX_BYTES).decode("utf-8", "replace")
        except OSError as e:
            return _fail(f"playlist unreadable: {type(e).__name__}")
        match = _MEDIA_SEQUENCE_RE.search(text)
        if match is None:
            return _fail("playlist has no #EXT-X-MEDIA-SEQUENCE tag")
        sequence = int(match.group(1))
        now_mono = self._monotonic()
        with self._lock:
            if sequence != self._last_sequence:
                self._last_sequence = sequence
                self._sequence_changed_at = now_mono
            unchanged_for = now_mono - self._sequence_changed_at
        data: dict[str, Any] = {
            "media_sequence": sequence,
            "sequence_unchanged_seconds": round(unchanged_for, 1),
        }

        newest: float | None = None
        for segment in self.stream_dir.glob(STREAM_SEGMENT_GLOB):
            if segment.is_symlink():
                continue
            try:
                mtime = segment.stat().st_mtime
            except OSError:
                continue
            newest = mtime if newest is None else max(newest, mtime)
        if newest is None:
            return _fail("playlist present but no segments", **data)
        age = self._clock() - newest
        data["newest_segment_age_seconds"] = round(age, 1)
        if age > STREAM_MAX_SEGMENT_AGE_SECONDS:
            return _fail(
                f"newest segment is {age:.0f}s old "
                f"(limit {STREAM_MAX_SEGMENT_AGE_SECONDS:.0f}s): the stream stopped",
                **data,
            )
        if unchanged_for > STREAM_MAX_SEQUENCE_STALL_SECONDS:
            return _fail(
                f"media sequence {sequence} unchanged for {unchanged_for:.0f}s "
                f"(limit {STREAM_MAX_SEQUENCE_STALL_SECONDS:.0f}s): the stream is not advancing",
                **data,
            )
        return _ok("advancing", **data)

    # ---- served web assets ----

    def _check_web_assets(self) -> dict[str, Any]:
        if self._web_manifest is None:
            return _fail(f"expected-asset manifest unavailable: {self._manifest_error}")
        if self.web_dir is None:
            return _fail("web_client_dir is not configured: no served assets to assert")
        if not self.web_dir.is_dir():
            return _fail("served web directory is absent")
        try:
            served = tree_digest(self.web_dir)
        except OSError as e:
            return _fail(f"served web tree unreadable: {type(e).__name__}")
        expected: dict[str, str] = self._web_manifest["files"]
        data: dict[str, Any] = {
            "expected_digest": self._web_manifest["digest"],
            "served_digest": served["digest"],
        }
        if served["digest"] == data["expected_digest"] and served["files"] == expected:
            return _ok("served assets match this build", **data)
        got: dict[str, str] = served["files"]
        changed = sorted(p for p in expected.keys() & got.keys() if expected[p] != got[p])
        missing = sorted(expected.keys() - got.keys())
        extra = sorted(got.keys() - expected.keys())
        return _fail(
            f"served assets differ from this build: {len(changed)} changed, "
            f"{len(missing)} missing, {len(extra)} extra",
            changed=changed[:WEB_ASSET_DIFF_LIST_LIMIT],
            missing=missing[:WEB_ASSET_DIFF_LIST_LIMIT],
            extra=extra[:WEB_ASSET_DIFF_LIST_LIMIT],
            **data,
        )


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    if len(args) != 2 or args[0] != "web-manifest":
        sys.stderr.write("usage: python -m mymts_helper.deep_health web-manifest <web-dir>\n")
        return 2
    sys.stdout.write(render_web_manifest(tree_digest(Path(args[1]))))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
