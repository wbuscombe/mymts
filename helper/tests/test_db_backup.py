"""Tests for helper/deploy/db_backup.py (MYMTS-025).

The module is standalone stdlib, because a deploy pipes it into the helper
container as `python - backup ...`, so it is loaded by file path. It is loaded
inside each test so that, before it existed, every case failed on its own.
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import sqlite3
import subprocess
import sys
import threading
from pathlib import Path

import pytest

MODULE_PATH = Path(__file__).resolve().parents[1] / "deploy" / "db_backup.py"


def _mod():
    spec = importlib.util.spec_from_file_location("mymts_db_backup", MODULE_PATH)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _make_db(path: Path, rows: int = 50) -> None:
    conn = sqlite3.connect(path)
    try:
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        conn.execute("INSERT INTO meta VALUES ('schema_version', '5')")
        conn.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, title TEXT NOT NULL)")
        conn.executemany(
            "INSERT INTO items (title) VALUES (?)", [(f"item {i}",) for i in range(rows)]
        )
        conn.commit()
    finally:
        conn.close()


def _content(path: Path) -> list[tuple]:
    # immutable=1: reading must not leave -wal/-shm beside the file, which a restore
    # onto that file would (rightly) refuse.
    conn = sqlite3.connect(f"{path.resolve().as_uri()}?mode=ro&immutable=1", uri=True)
    try:
        return conn.execute(
            "SELECT 'meta', key, value FROM meta UNION ALL "
            "SELECT 'items', id, title FROM items ORDER BY 1, 2"
        ).fetchall()
    finally:
        conn.close()


@pytest.fixture
def source(tmp_path: Path) -> Path:
    db = tmp_path / "live" / "mymts-helper.db"
    db.parent.mkdir()
    _make_db(db)
    return db


# ---- a backup is produced, and its checksum is recorded ----


def test_backup_is_produced(tmp_path: Path, source: Path) -> None:
    m = _mod()
    result = m.backup(source, tmp_path / "backups")
    path = Path(result["path"])
    assert path.is_file()
    assert path.read_bytes()[:16] == m.SQLITE_HEADER
    assert _content(path) == _content(source)


def test_backup_checksum_is_recorded(tmp_path: Path, source: Path) -> None:
    m = _mod()
    result = m.backup(source, tmp_path / "backups")
    path = Path(result["path"])
    sidecar = Path(result["sidecar"])
    assert sidecar == path.with_name(path.name + m.SIDECAR_SUFFIX)
    assert result["sha256"] == _sha(path)
    assert sidecar.read_text(encoding="utf-8") == f"{_sha(path)}  {path.name}\n"


def test_backup_never_overwrites_an_existing_backup(tmp_path: Path, source: Path) -> None:
    m = _mod()
    out = tmp_path / "backups"
    first = m.backup(source, out, name="fixed.db")
    before = _sha(Path(first["path"]))
    with pytest.raises(m.BackupError):
        m.backup(source, out, name="fixed.db")
    assert _sha(Path(first["path"])) == before


def test_backup_of_a_missing_database_creates_nothing(tmp_path: Path) -> None:
    m = _mod()
    missing = tmp_path / "absent" / "mymts-helper.db"
    with pytest.raises(m.BackupError):
        m.backup(missing, tmp_path / "backups")
    assert not missing.exists()
    assert not missing.parent.exists()


# ---- restore into a NEW location reproduces the source byte for byte ----


def test_restore_to_new_location_reproduces_backup_byte_for_byte(
    tmp_path: Path, source: Path
) -> None:
    m = _mod()
    backup = m.backup(source, tmp_path / "backups")
    restored = m.restore(backup["path"], dest_dir=tmp_path / "restored")
    path = Path(restored["path"])
    assert path.parent == tmp_path / "restored"
    assert _sha(path) == backup["sha256"] == restored["sha256"]
    assert _content(path) == _content(source)
    assert restored["preserved"] is None


# ---- restore refuses to overwrite an existing file unless a target is explicit ----


def test_restore_refuses_to_overwrite_existing_file_without_explicit_target(
    tmp_path: Path, source: Path
) -> None:
    m = _mod()
    backup = m.backup(source, tmp_path / "backups")
    dest = tmp_path / "restored"
    dest.mkdir()
    occupied = dest / "occupied.db"
    occupied.write_bytes(b"operator data that must survive")
    with pytest.raises(m.RestoreRefused):
        m.restore(backup["path"], dest_dir=dest, name="occupied.db")
    assert occupied.read_bytes() == b"operator data that must survive"


def test_restore_onto_explicit_target_preserves_the_prior_file(
    tmp_path: Path, source: Path
) -> None:
    m = _mod()
    backup = m.backup(source, tmp_path / "backups")
    target = tmp_path / "volume" / "mymts-helper.db"
    target.parent.mkdir()
    _make_db(target, rows=7)
    prior_sha, prior_content = _sha(target), _content(target)
    before = set(target.parent.iterdir())

    restored = m.restore(backup["path"], target=target)

    assert _content(target) == _content(source)
    assert _sha(target) == backup["sha256"]
    preserved = Path(restored["preserved"])
    assert m.PRE_RESTORE_MARKER in preserved.name
    assert _sha(preserved) == prior_sha
    assert _content(preserved) == prior_content
    assert before <= set(target.parent.iterdir())  # nothing removed


def test_explicit_target_with_wal_companion_is_refused(tmp_path: Path, source: Path) -> None:
    m = _mod()
    backup = m.backup(source, tmp_path / "backups")
    target = tmp_path / "volume" / "mymts-helper.db"
    target.parent.mkdir()
    target.write_bytes(b"prior bytes")
    wal = target.with_name(target.name + "-wal")
    wal.write_bytes(b"uncheckpointed frames")
    with pytest.raises(m.RestoreRefused):
        m.restore(backup["path"], target=target)
    assert target.read_bytes() == b"prior bytes"
    assert wal.read_bytes() == b"uncheckpointed frames"


# ---- a corrupted backup is detected and refused ----


def test_corrupted_backup_is_refused(tmp_path: Path, source: Path) -> None:
    m = _mod()
    backup = m.backup(source, tmp_path / "backups")
    path = Path(backup["path"])
    raw = bytearray(path.read_bytes())
    raw[len(raw) // 2] ^= 0xFF
    path.write_bytes(bytes(raw))
    dest = tmp_path / "restored"
    with pytest.raises(m.RestoreRefused, match="checksum"):
        m.restore(path, dest_dir=dest)
    assert not dest.exists() or not any(dest.iterdir())


def test_damaged_backup_with_a_matching_checksum_is_refused(
    tmp_path: Path, source: Path
) -> None:
    m = _mod()
    backup = m.backup(source, tmp_path / "backups")
    path = Path(backup["path"])
    raw = bytearray(path.read_bytes())
    raw[:16] = b"not a database!!"
    path.write_bytes(bytes(raw))
    Path(backup["sidecar"]).write_text(f"{_sha(path)}  {path.name}\n", encoding="utf-8")
    with pytest.raises(m.RestoreRefused):
        m.restore(path, dest_dir=tmp_path / "restored")


# ---- consistent under a concurrent writer ----


def test_backup_is_consistent_under_a_concurrent_writer(tmp_path: Path) -> None:
    m = _mod()
    db = tmp_path / "busy.db"
    conn = sqlite3.connect(db)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("CREATE TABLE a (id INTEGER PRIMARY KEY, pad BLOB)")
    conn.execute("CREATE TABLE b (id INTEGER PRIMARY KEY, pad BLOB)")
    conn.commit()
    conn.close()

    stop = threading.Event()

    def writer() -> None:
        w = sqlite3.connect(db, timeout=30)
        try:
            while not stop.is_set():
                # One transaction inserts into both tables: any consistent
                # snapshot therefore has equal row counts.
                with w:
                    w.execute("INSERT INTO a (pad) VALUES (randomblob(512))")
                    w.execute("INSERT INTO b (pad) VALUES (randomblob(512))")
        finally:
            w.close()

    t = threading.Thread(target=writer)
    t.start()
    try:
        results = [m.backup(db, tmp_path / "backups", name=f"b{i}.db") for i in range(5)]
    finally:
        stop.set()
        t.join()

    for r in results:
        c = sqlite3.connect(r["path"])
        try:
            assert c.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
            count_a = c.execute("SELECT COUNT(*) FROM a").fetchone()[0]
            count_b = c.execute("SELECT COUNT(*) FROM b").fetchone()[0]
        finally:
            c.close()
        assert count_a == count_b


# ---- the piped form a deploy uses: `python - <command>` with the source on stdin ----


def test_piped_cli_round_trip_and_refusal(tmp_path: Path, source: Path) -> None:
    code = MODULE_PATH.read_bytes()

    def run(*args: str) -> subprocess.CompletedProcess[bytes]:
        return subprocess.run(
            [sys.executable, "-", *args], input=code, capture_output=True, check=False
        )

    b = run("backup", "--db", str(source), "--out-dir", str(tmp_path / "backups"))
    assert b.returncode == 0, b.stderr
    backup = json.loads(b.stdout)

    r = run("restore", "--backup", backup["path"], "--dest-dir", str(tmp_path / "restored"))
    assert r.returncode == 0, r.stderr
    assert _content(Path(json.loads(r.stdout)["path"])) == _content(source)

    damaged = Path(backup["path"])
    raw = bytearray(damaged.read_bytes())
    raw[-1] ^= 0xFF
    damaged.write_bytes(bytes(raw))
    refused = run("restore", "--backup", str(damaged), "--dest-dir", str(tmp_path / "again"))
    assert refused.returncode == _mod().EXIT_REFUSED
    assert b"checksum" in refused.stderr


# ---- a stopped database whose last reader was read-only keeps its companions ----


def test_checkpoint_folds_leftover_companions_so_a_restore_can_proceed(
    tmp_path: Path, source: Path
) -> None:
    m = _mod()
    backup = m.backup(source, tmp_path / "backups")
    target = tmp_path / "volume" / "mymts-helper.db"
    target.parent.mkdir()
    _make_db(target, rows=7)
    reader = sqlite3.connect(f"{target.resolve().as_uri()}?mode=ro", uri=True)
    reader.execute("SELECT COUNT(*) FROM items").fetchone()
    reader.close()
    companions = [target.with_name(target.name + s) for s in ("-wal", "-shm")]
    assert any(c.exists() for c in companions)
    prior_content = _content(target)
    with pytest.raises(m.RestoreRefused):
        m.restore(backup["path"], target=target)

    result = m.checkpoint(target)

    assert result["companions_remaining"] == []
    assert not any(c.exists() for c in companions)
    assert _content(target) == prior_content
    restored = m.restore(backup["path"], target=target)
    assert _content(Path(restored["preserved"])) == prior_content
    assert _content(target) == _content(source)


def test_checkpoint_never_creates_a_missing_database(tmp_path: Path) -> None:
    m = _mod()
    missing = tmp_path / "absent.db"
    with pytest.raises(m.BackupError):
        m.checkpoint(missing)
    assert not missing.exists()
