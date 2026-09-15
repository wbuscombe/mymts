#!/usr/bin/env python3
"""Consistent SQLite backup and non-destructive restore for the helper database.

Standalone and stdlib-only on purpose: a deploy pipes this file into whatever python
is at hand (the running helper container for a backup, a one-shot container for a
restore), so it cannot import the helper package.

  backup   SQLite's online backup API copies the database as ONE consistent
           snapshot; the copy is integrity-checked and its sha256 written to a
           sidecar. Never a raw file copy of a live database.
  verify   sidecar checksum, SQLite header, then PRAGMA integrity_check.
  checkpoint
           folds a STOPPED database's WAL into its main file so the -wal/-shm a
           read-only last reader leaves behind are gone and a restore can proceed.
  restore  verifies first, then writes to a NEW file, or to a target the caller
           names explicitly. Nothing is ever deleted: an existing target is first
           preserved byte for byte as `<target>.pre-restore-<utc>`, and a target
           with -wal/-shm/-journal companions (a live or uncleanly closed database)
           is refused. No cleanup, no retention count, no implicit overwrite.

Usage (also as `python - <command> ...` with this file on stdin):
  db_backup.py backup  --db PATH --out-dir DIR [--name NAME]
  db_backup.py verify  --backup PATH
  db_backup.py restore --backup PATH (--target PATH | --dest-dir DIR [--name NAME])

Prints one JSON object on success. Exit codes: 0 ok, 2 usage, 3 refused, 4 error.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sqlite3
import sys
from datetime import datetime, timezone
from pathlib import Path

SQLITE_HEADER = b"SQLite format 3\x00"
SIDECAR_SUFFIX = ".sha256"
PRE_RESTORE_MARKER = ".pre-restore-"
# Present next to a database that is open, or was not closed cleanly.
COMPANION_SUFFIXES = ("-wal", "-shm", "-journal")
# How long the backup waits on a locked source before failing.
SOURCE_TIMEOUT_SECONDS = 30.0
HASH_CHUNK_BYTES = 1 << 20

EXIT_OK = 0
EXIT_USAGE = 2
EXIT_REFUSED = 3
EXIT_ERROR = 4


class BackupError(Exception):
    """The backup could not be produced."""


class RestoreRefused(Exception):
    """The backup cannot be trusted or the destination is unsafe; nothing was written."""


class RestoreError(Exception):
    """A verified restore failed while writing."""


def utc_stamp() -> str:
    # timezone.utc rather than datetime.UTC: this file also runs on older pythons.
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")  # noqa: UP017


def sha256_file(path: str | Path) -> str:
    h = hashlib.sha256()
    with Path(path).open("rb") as f:
        for chunk in iter(lambda: f.read(HASH_CHUNK_BYTES), b""):
            h.update(chunk)
    return h.hexdigest()


def _plain_name(name: str) -> str:
    if not name or Path(name).name != name or name in (".", ".."):
        raise ValueError(f"not a plain file name: {name!r}")
    return name


def _ro_uri(path: Path, *, immutable: bool = False) -> str:
    uri = f"{path.resolve().as_uri()}?mode=ro"
    return uri + "&immutable=1" if immutable else uri


def _integrity(path: Path) -> str:
    # immutable=1: checking a backup never creates companion files beside it.
    conn = sqlite3.connect(_ro_uri(path, immutable=True), uri=True)
    try:
        return str(conn.execute("PRAGMA integrity_check").fetchone()[0])
    finally:
        conn.close()


def backup(db_path: str | Path, out_dir: str | Path, *, name: str | None = None) -> dict:
    source = Path(db_path)
    if not source.is_file():
        raise BackupError(f"source database not found: {source}")
    out = Path(out_dir)
    try:
        dest = out / _plain_name(name or f"mymts-helper-{utc_stamp()}.db")
    except ValueError as e:
        raise BackupError(str(e)) from e
    sidecar = dest.with_name(dest.name + SIDECAR_SUFFIX)
    if dest.exists() or sidecar.exists():
        raise BackupError(f"refusing to overwrite an existing backup: {dest.name}")
    out.mkdir(parents=True, exist_ok=True)
    try:
        with dest.open("xb"):
            pass
    except FileExistsError as e:
        raise BackupError(f"refusing to overwrite an existing backup: {dest.name}") from e

    try:
        src = sqlite3.connect(_ro_uri(source), uri=True, timeout=SOURCE_TIMEOUT_SECONDS)
        try:
            dst = sqlite3.connect(str(dest))
            try:
                # pages=-1 (the default) copies the whole database in one step inside
                # one read transaction, so the copy is a single consistent snapshot
                # while other connections keep writing: a WAL reader holds a fixed
                # snapshot and writers only append to the WAL.
                src.backup(dst)
                # A standalone file: no WAL companions to carry with the backup.
                dst.execute("PRAGMA journal_mode=DELETE")
            finally:
                dst.close()
        finally:
            src.close()
    except sqlite3.Error as e:
        raise BackupError(f"sqlite backup failed: {type(e).__name__}: {e}") from e

    integrity = _integrity(dest)
    if integrity != "ok":
        raise BackupError(f"backup failed integrity_check: {integrity}")
    digest = sha256_file(dest)
    with sidecar.open("x", encoding="utf-8") as f:
        f.write(f"{digest}  {dest.name}\n")
    return {"path": str(dest), "sha256": digest, "sidecar": str(sidecar), "integrity": integrity}


def verify_backup(backup_path: str | Path) -> str:
    """Return the verified sha256, or raise RestoreRefused."""
    path = Path(backup_path)
    sidecar = path.with_name(path.name + SIDECAR_SUFFIX)
    if not path.is_file():
        raise RestoreRefused(f"backup not found: {path}")
    if not sidecar.is_file():
        raise RestoreRefused(f"no checksum sidecar for {path.name}: an unverifiable backup")
    fields = sidecar.read_text(encoding="utf-8").split()
    if len(fields) != 2 or fields[1] != path.name:
        raise RestoreRefused(f"malformed checksum sidecar for {path.name}")
    actual = sha256_file(path)
    if actual != fields[0]:
        raise RestoreRefused(
            f"checksum mismatch for {path.name}: recorded {fields[0][:12]}, actual {actual[:12]}"
        )
    with path.open("rb") as f:
        if f.read(len(SQLITE_HEADER)) != SQLITE_HEADER:
            raise RestoreRefused(f"{path.name} is not an SQLite database")
    try:
        integrity = _integrity(path)
    except sqlite3.Error as e:
        raise RestoreRefused(f"integrity_check could not run: {type(e).__name__}: {e}") from e
    if integrity != "ok":
        raise RestoreRefused(f"integrity_check failed for {path.name}: {integrity}")
    return actual


def checkpoint(db_path: str | Path) -> dict:
    """Fold a stopped database's WAL into the main file so its companions go away.

    mode=rw never creates a missing file. `wal_checkpoint(TRUNCATE)` copies every
    WAL frame into the database first, and only then does closing the last
    connection let SQLite itself remove its -wal/-shm, so no row is lost. If another
    connection still holds the database, the companions stay and are reported, and a
    restore onto it is still refused.
    """
    path = Path(db_path)
    if not path.is_file():
        raise BackupError(f"database not found: {path}")
    try:
        conn = sqlite3.connect(
            f"{path.resolve().as_uri()}?mode=rw", uri=True, timeout=SOURCE_TIMEOUT_SECONDS
        )
        try:
            busy, wal_frames, done = conn.execute("PRAGMA wal_checkpoint(TRUNCATE)").fetchone()
        finally:
            conn.close()
    except sqlite3.Error as e:
        raise BackupError(f"checkpoint failed: {type(e).__name__}: {e}") from e
    remaining = [
        path.name + s for s in COMPANION_SUFFIXES if path.with_name(path.name + s).exists()
    ]
    return {
        "path": str(path),
        "busy": busy,
        "wal_frames": wal_frames,
        "checkpointed_frames": done,
        "companions_remaining": remaining,
    }


def _copy_verified(src: Path, dest: Path, expected_sha: str) -> None:
    """Copy into a file that must not exist yet, fsync it, and check its sha256."""
    with src.open("rb") as fin, dest.open("xb") as fout:
        for chunk in iter(lambda: fin.read(HASH_CHUNK_BYTES), b""):
            fout.write(chunk)
        fout.flush()
        os.fsync(fout.fileno())
    if sha256_file(dest) != expected_sha:
        raise RestoreError(f"written copy {dest.name} does not match its source checksum")


def restore(
    backup_path: str | Path,
    *,
    target: str | Path | None = None,
    dest_dir: str | Path | None = None,
    name: str | None = None,
) -> dict:
    if (target is None) == (dest_dir is None):
        raise ValueError("pass exactly one of target or dest_dir")
    source = Path(backup_path)
    digest = verify_backup(source)
    stamp = utc_stamp()

    if target is None:
        out = Path(dest_dir)
        try:
            path = out / _plain_name(name or f"restored-{stamp}-{source.name}")
        except ValueError as e:
            raise RestoreRefused(str(e)) from e
        if path.exists():
            raise RestoreRefused(
                f"refusing to overwrite existing file {path.name}: name a target explicitly"
            )
        out.mkdir(parents=True, exist_ok=True)
        try:
            _copy_verified(source, path, digest)
        except FileExistsError as e:
            raise RestoreRefused(f"refusing to overwrite existing file {path.name}") from e
        return {"path": str(path), "sha256": digest, "preserved": None}

    tgt = Path(target)
    companions = [tgt.name + s for s in COMPANION_SUFFIXES if tgt.with_name(tgt.name + s).exists()]
    if companions:
        raise RestoreRefused(
            f"target has {', '.join(companions)}: a live or uncleanly closed database; "
            "stop the helper cleanly first"
        )
    if tgt.exists() and not tgt.is_file():
        raise RestoreRefused(f"target {tgt.name} exists and is not a regular file")

    preserved: Path | None = None
    if tgt.exists():
        # The prior database survives byte for byte before anything replaces it.
        preserved = tgt.with_name(f"{tgt.name}{PRE_RESTORE_MARKER}{stamp}")
        _copy_verified(tgt, preserved, sha256_file(tgt))
    staged = tgt.with_name(f"{tgt.name}.restore-{stamp}.tmp")
    _copy_verified(source, staged, digest)
    os.replace(staged, tgt)
    if sha256_file(tgt) != digest:
        raise RestoreError(f"restored {tgt.name} does not match the backup checksum")
    return {
        "path": str(tgt),
        "sha256": digest,
        "preserved": str(preserved) if preserved is not None else None,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="db_backup.py", description="Consistent SQLite backup and non-destructive restore."
    )
    sub = parser.add_subparsers(dest="command", required=True)
    b = sub.add_parser("backup")
    b.add_argument("--db", required=True)
    b.add_argument("--out-dir", required=True)
    b.add_argument("--name")
    v = sub.add_parser("verify")
    v.add_argument("--backup", required=True)
    c = sub.add_parser("checkpoint")
    c.add_argument("--db", required=True)
    r = sub.add_parser("restore")
    r.add_argument("--backup", required=True)
    where = r.add_mutually_exclusive_group(required=True)
    where.add_argument("--target")
    where.add_argument("--dest-dir")
    r.add_argument("--name")
    try:
        args = parser.parse_args(argv)
    except SystemExit as e:
        return EXIT_USAGE if e.code else EXIT_OK

    try:
        if args.command == "backup":
            result = backup(args.db, args.out_dir, name=args.name)
        elif args.command == "verify":
            result = {"path": args.backup, "sha256": verify_backup(args.backup), "verified": True}
        elif args.command == "checkpoint":
            result = checkpoint(args.db)
        else:
            result = restore(
                args.backup, target=args.target, dest_dir=args.dest_dir, name=args.name
            )
    except RestoreRefused as e:
        sys.stderr.write(f"REFUSED: {e}\n")
        return EXIT_REFUSED
    except (BackupError, RestoreError, ValueError, OSError, sqlite3.Error) as e:
        sys.stderr.write(f"ERROR: {type(e).__name__}: {e}\n")
        return EXIT_ERROR
    sys.stdout.write(json.dumps(result, sort_keys=True) + "\n")
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
