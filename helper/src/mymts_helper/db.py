"""SQLite connection + migration runner.

WAL mode for read concurrency without process boundary cost. Connections
are created per-coroutine/request because sqlite3 connection objects are
not safe to share across threads by default — the per-connection cost on
a local file is negligible.

Migrations are numbered SQL files in `migrations/`. The migration runner:
  - opens an exclusive lock so two helpers can't race on first deploy,
  - reads the `meta.schema_version`, defaults to 0,
  - applies each `NNN_*.sql` file with NNN > version, in order,
  - records the new version in `meta`.

Failure during a migration aborts the process. Refusing-to-start with a
distinct exit code is the contract from Stage 1's spec; honoring it here
means an inconsistent on-disk schema cannot serve real requests.
"""

from __future__ import annotations

import logging
import re
import sqlite3
from collections.abc import Iterator
from contextlib import contextmanager
from importlib.resources import files
from pathlib import Path

log = logging.getLogger("mymts_helper.db")

MIGRATIONS_RE = re.compile(r"^(\d{3,})_.+\.sql$")


def _migration_files() -> list[tuple[int, Path]]:
    pkg = files("mymts_helper").joinpath("migrations")
    out: list[tuple[int, Path]] = []
    for entry in pkg.iterdir():
        if not entry.is_file():
            continue
        m = MIGRATIONS_RE.match(entry.name)
        if m:
            out.append((int(m.group(1)), Path(str(entry))))
    out.sort(key=lambda x: x[0])
    return out


def connect(path: str | Path, *, init: bool = False) -> sqlite3.Connection:
    """Open a connection. Always sets WAL + sane pragmas.

    init=True only matters on the first call for a new DB file; subsequent
    calls find WAL already set and the PRAGMAs are idempotent.
    """
    conn = sqlite3.connect(path, isolation_level=None, timeout=10.0)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL;")
    conn.execute("PRAGMA synchronous=NORMAL;")
    conn.execute("PRAGMA foreign_keys=ON;")
    return conn


def current_schema_version(conn: sqlite3.Connection) -> int:
    row = conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='meta'"
    ).fetchone()
    if row is None:
        return 0
    row = conn.execute("SELECT value FROM meta WHERE key='schema_version'").fetchone()
    if row is None:
        return 0
    try:
        return int(row["value"])
    except (TypeError, ValueError):
        return 0


def migrate(path: str | Path) -> int:
    """Apply all pending migrations. Returns the final schema_version.

    Concurrency note: sqlite3's `executescript` implicitly commits any open
    transaction, so wrapping the whole apply in one BEGIN/COMMIT is unsafe.
    Instead we acquire `PRAGMA locking_mode=EXCLUSIVE` for the duration of
    the migrate connection — that blocks any other connection from
    acquiring write locks until this one closes, which is the race-safety
    we need on first deploy. Each migration's DDL is one executescript
    that auto-commits; the `meta` row update follows immediately after.
    If a migration mid-apply fails partway, the schema is in a partial
    state and the process refuses to continue serving requests — that's
    the right outcome per the Stage 1 spec.
    """
    conn = connect(path)
    try:
        conn.execute("PRAGMA locking_mode=EXCLUSIVE;")
        # First read locks the database; first write would block any racer.
        conn.execute("SELECT 1")
        version = current_schema_version(conn)
        pending = [(n, p) for (n, p) in _migration_files() if n > version]
        if not pending:
            return version
        log.info("db_migrate_start", extra={"current": version, "pending": len(pending)})
        for n, p in pending:
            log.info("db_migrate_apply", extra={"migration": p.name})
            sql = p.read_text()
            # executescript runs the file in autocommit; on failure the
            # partial DDL stays applied (sqlite has no transactional DDL
            # rollback for many statements) — the outer `meta.schema_version`
            # update is therefore the source of truth for "did this
            # migration finish."
            conn.executescript(sql)
            conn.execute(
                "INSERT INTO meta(key, value) VALUES('schema_version', ?) "
                "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                (str(n),),
            )
        return pending[-1][0]
    finally:
        # Releasing back to NORMAL via PRAGMA isn't enough on macOS unless
        # we also close — and close is what releases the EXCLUSIVE lock
        # for the next connection.
        conn.close()


@contextmanager
def connection_scope(path: str | Path) -> Iterator[sqlite3.Connection]:
    """Open a connection, yield it, and close it in the SAME call frame.

    This is the read-path replacement for a FastAPI `Depends()` generator
    that yields a connection. The bug it fixes: a *sync* route function
    runs in Starlette's anyio threadpool, and FastAPI drives a sync
    `yield`-dependency's setup (open) and teardown (`close()`) through
    two separate `run_in_threadpool` calls — which can land on two
    different threadpool threads. sqlite3 connection objects are bound to
    the thread that created them, so closing on a different thread raises
    `sqlite3.ProgrammingError: SQLite objects created in a thread can
    only be used in that same thread`, surfacing as an intermittent
    HTTP 500 on `/api/feed`.

    Using this `with`-scope *inside* the route body keeps open + use +
    close within the single threadpool thread that runs the route, so
    the connection never crosses a thread boundary. (The whole sync route
    body is one `run_in_threadpool` invocation, hence one thread.)
    """
    conn = connect(path)
    try:
        yield conn
    finally:
        conn.close()


@contextmanager
def transaction(conn: sqlite3.Connection) -> Iterator[sqlite3.Connection]:
    """BEGIN/COMMIT/ROLLBACK helper. Use for any multi-statement write."""
    conn.execute("BEGIN;")
    try:
        yield conn
        conn.execute("COMMIT;")
    except Exception:
        conn.execute("ROLLBACK;")
        raise
