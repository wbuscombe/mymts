"""SQLite persistence for sources + feed_items.

Synchronous sqlite3 functions wrapped so the poller can `run_in_executor`
them without blocking the loop. Stage 2 traffic is light; this is fine.
"""

from __future__ import annotations

import logging
import sqlite3
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import UTC, datetime
from urllib.parse import urlsplit, urlunsplit

from .parser import ParsedItem

log = logging.getLogger("mymts_helper.feeds.store")


def _utcnow_iso() -> str:
    return datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%S.000Z")


@dataclass(frozen=True)
class SourceRow:
    id: int
    url: str
    label: str
    enabled: bool
    last_fetch_at: str | None
    last_success_at: str | None
    last_error: str | None
    error_count: int


def list_sources(conn: sqlite3.Connection, *, enabled_only: bool = False) -> list[SourceRow]:
    sql = (
        "SELECT id, url, label, enabled, last_fetch_at, last_success_at, "
        "last_error, error_count FROM sources"
    )
    params: tuple = ()
    if enabled_only:
        sql += " WHERE enabled=1"
    sql += " ORDER BY id"
    rows = conn.execute(sql, params).fetchall()
    return [
        SourceRow(
            id=r["id"],
            url=r["url"],
            label=r["label"],
            enabled=bool(r["enabled"]),
            last_fetch_at=r["last_fetch_at"],
            last_success_at=r["last_success_at"],
            last_error=r["last_error"],
            error_count=r["error_count"],
        )
        for r in rows
    ]


def upsert_source(conn: sqlite3.Connection, *, url: str, label: str) -> int:
    """Idempotent registration. Returns the source id."""
    conn.execute(
        "INSERT INTO sources(url, label) VALUES (?, ?) "
        "ON CONFLICT(url) DO UPDATE SET label=excluded.label",
        (url, label),
    )
    row = conn.execute("SELECT id FROM sources WHERE url=?", (url,)).fetchone()
    return int(row["id"])


def _match_key(url: str) -> str:
    """A CONSERVATIVE, semantics-preserving key for COMPARING two source URLs
    — never for storage or serving (the stored/polled/served URL stays the
    operator's exact string).

    Applies ONLY rules that RFC 3986 makes safe (so a trivially-different
    repoint isn't seen as an orphan): strip surrounding whitespace, lowercase
    the SCHEME and HOST (both case-insensitive), and treat a single trailing
    `/` on the path as equivalent. Everything else — PATH content/case, QUERY,
    FRAGMENT, PORT, USERINFO, and http-vs-https — is preserved EXACTLY, so
    genuinely-distinct endpoints NEVER collapse (a missed match merely re-fetches
    a cache; a wrong match would silently drop a real source). Falls back to the
    stripped string if the URL won't parse, so the prune can never crash.
    """
    s = url.strip()
    try:
        parts = urlsplit(s)
        host = (parts.hostname or "").lower()
        userinfo = ""
        if parts.username is not None:
            userinfo = parts.username
            if parts.password is not None:
                userinfo += ":" + parts.password
            userinfo += "@"
        port = f":{parts.port}" if parts.port is not None else ""
        path = parts.path
        if len(path) > 1 and path.endswith("/"):
            path = path[:-1]
        return urlunsplit((parts.scheme.lower(), f"{userinfo}{host}{port}", path,
                           parts.query, parts.fragment))
    except ValueError:
        # e.g. a non-numeric port makes .port raise — don't normalize, compare
        # the stripped string (conservative: a missed match, never a wrong one).
        return s


def delete_sources_not_in(conn: sqlite3.Connection, keep_urls: set[str]) -> int:
    """Remove sources whose URL is not in `keep_urls` (their feed_items go too,
    via `ON DELETE CASCADE`). Reconciles the DB to the seed file: seed.json is
    the source of truth for the feed list (there is no add-source API), so a
    source removed from the seed — or repointed to a new URL — must not linger
    in the persistent DB still being polled. Returns the count deleted.

    Matching uses `_match_key` (CONSERVATIVE normalization) so a still-wanted
    source that differs from its seed URL only cosmetically (trailing slash,
    scheme/host case) isn't pruned as a false orphan — while genuinely-distinct
    URLs stay distinct. The stored URL is never rewritten; normalization is for
    the comparison only.
    """
    keep_keys = {_match_key(u) for u in keep_urls}
    rows = conn.execute("SELECT id, url FROM sources").fetchall()
    stale = [r["id"] for r in rows if _match_key(r["url"]) not in keep_keys]
    for sid in stale:
        conn.execute("DELETE FROM sources WHERE id=?", (sid,))
    return len(stale)


def record_fetch_success(conn: sqlite3.Connection, source_id: int) -> None:
    now = _utcnow_iso()
    conn.execute(
        "UPDATE sources SET last_fetch_at=?, last_success_at=?, "
        "last_error=NULL, error_count=0 WHERE id=?",
        (now, now, source_id),
    )


def record_fetch_failure(conn: sqlite3.Connection, source_id: int, reason: str) -> None:
    now = _utcnow_iso()
    # Truncate reason to keep the row small; a useful reason fits comfortably.
    conn.execute(
        "UPDATE sources SET last_fetch_at=?, last_error=?, "
        "error_count=error_count+1 WHERE id=?",
        (now, reason[:200], source_id),
    )


def insert_items(
    conn: sqlite3.Connection, source_id: int, items: Iterable[ParsedItem]
) -> int:
    """Bulk insert with per-(source,guid) dedup. Returns the count newly inserted."""
    sql = (
        "INSERT INTO feed_items(source_id, guid, title, summary, link, published_at, fetched_at) "
        "VALUES (?, ?, ?, ?, ?, ?, ?) "
        "ON CONFLICT(source_id, guid) DO NOTHING"
    )
    now = _utcnow_iso()
    inserted = 0
    for item in items:
        cur = conn.execute(
            sql,
            (
                source_id,
                item.guid,
                item.title,
                item.summary or None,
                item.link or None,
                item.published_at,
                now,
            ),
        )
        if cur.rowcount > 0:
            inserted += 1
    return inserted


def recent_items(
    conn: sqlite3.Connection,
    *,
    limit: int = 200,
    since: str | None = None,
) -> list[dict]:
    """Return the newest `limit` items. Items are ordered by published_at DESC
    with a fetched_at fallback for entries with no published date.
    """
    sql = (
        "SELECT fi.id, fi.guid, fi.title, fi.summary, fi.link, fi.published_at, "
        "fi.fetched_at, s.label AS source_label, s.url AS source_url "
        "FROM feed_items fi "
        "JOIN sources s ON s.id = fi.source_id "
    )
    params: list = []
    if since:
        sql += "WHERE COALESCE(fi.published_at, fi.fetched_at) > ? "
        params.append(since)
    sql += (
        "ORDER BY COALESCE(fi.published_at, fi.fetched_at) DESC LIMIT ?"
    )
    params.append(int(limit))
    return [dict(r) for r in conn.execute(sql, params).fetchall()]


def total_items(conn: sqlite3.Connection) -> int:
    row = conn.execute("SELECT COUNT(*) AS n FROM feed_items").fetchone()
    return int(row["n"])


def retention_sweep(conn: sqlite3.Connection, *, days: int) -> int:
    """Delete items whose effective timestamp (published_at fallback to fetched_at)
    is older than `days` days. Returns the number deleted.
    """
    sql = (
        "DELETE FROM feed_items WHERE "
        "COALESCE(published_at, fetched_at) < datetime('now', ?)"
    )
    cur = conn.execute(sql, (f"-{int(days)} days",))
    return cur.rowcount
