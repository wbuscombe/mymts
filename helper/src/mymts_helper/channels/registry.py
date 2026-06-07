"""Channel registry — storage + URL validation at the boundary.

Stage 2 supports only `kind='hls'`: the helper accepts a direct HLS URL,
probes it, and exposes the result. The `kind` column has a CHECK constraint
that admits exactly that — a future migration adds `'youtube'` when yt-dlp
arrives in its own sandboxed sidecar.
"""

from __future__ import annotations

import json
import logging
import re
import sqlite3
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

log = logging.getLogger("mymts_helper.channels.registry")

# Slug rule: lowercase ASCII + digits + dashes; matches what the TV will
# use as a stable identifier. Stricter than necessary on purpose — slugs
# end up in URLs and we don't want injection surfaces.
# Start + end with alphanumeric; dashes only between. Max 64 chars total.
SLUG_RE = re.compile(r"^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$")


class RegistryError(ValueError):
    """Raised by the registry's validators."""


@dataclass(frozen=True)
class ChannelRow:
    id: int
    slug: str
    label: str
    kind: str
    source_url: str
    enabled: bool
    current_url: str | None
    status: str
    last_check_at: str | None
    last_success_at: str | None
    last_error: str | None
    error_count: int
    # Web-client mixed-content hint: True = HTTPS-clean (browser-playable),
    # False = http:// sub-resource found (TV-only), None = unclassified.
    browser_playable: bool | None = None


def validate_slug(slug: str) -> str:
    if not isinstance(slug, str) or not SLUG_RE.match(slug):
        raise RegistryError(f"invalid_slug: {slug!r}")
    return slug


def validate_hls_url(url: str) -> str:
    """Structured validation — not regex matching against the URL string.

    Mirrors the principle the v1.1 web-app review forced us to internalise:
    structured parsing, not loose pattern matching. The url must be https,
    have a non-empty host, no userinfo, port 443 or default, and a path
    that looks like an m3u8 (extension or query name).
    """
    if not isinstance(url, str):
        raise RegistryError("hls_url_not_string")
    parsed = urlparse(url)
    if parsed.scheme != "https":
        raise RegistryError(f"hls_url_scheme: {parsed.scheme!r} (https only)")
    if not parsed.hostname:
        raise RegistryError("hls_url_no_host")
    if parsed.username is not None or parsed.password is not None:
        raise RegistryError("hls_url_userinfo")
    if parsed.port not in (None, 443):
        raise RegistryError(f"hls_url_port: {parsed.port}")
    path = parsed.path.lower()
    query = parsed.query.lower()
    looks_like_m3u8 = (
        path.endswith(".m3u8")
        or "m3u8" in query
        or path.endswith("/master")
        or path.endswith("/playlist")
    )
    if not looks_like_m3u8:
        raise RegistryError(f"hls_url_path_unlikely_m3u8: {parsed.path!r}")
    return url


def upsert_channel(
    conn: sqlite3.Connection,
    *,
    slug: str,
    label: str,
    source_url: str,
    kind: str = "hls",
) -> int:
    validate_slug(slug)
    if kind != "hls":
        raise RegistryError(f"unsupported_kind: {kind!r}")
    validate_hls_url(source_url)
    conn.execute(
        "INSERT INTO channels(slug, label, kind, source_url) VALUES (?, ?, ?, ?) "
        "ON CONFLICT(slug) DO UPDATE SET "
        "label=excluded.label, source_url=excluded.source_url",
        (slug, label, kind, source_url),
    )
    row = conn.execute("SELECT id FROM channels WHERE slug=?", (slug,)).fetchone()
    return int(row["id"])


def list_channels(conn: sqlite3.Connection, *, enabled_only: bool = False) -> list[ChannelRow]:
    sql = (
        "SELECT id, slug, label, kind, source_url, enabled, current_url, status, "
        "last_check_at, last_success_at, last_error, error_count, browser_playable "
        "FROM channels"
    )
    if enabled_only:
        sql += " WHERE enabled=1"
    sql += " ORDER BY slug"
    return [
        ChannelRow(
            id=r["id"],
            slug=r["slug"],
            label=r["label"],
            kind=r["kind"],
            source_url=r["source_url"],
            enabled=bool(r["enabled"]),
            current_url=r["current_url"],
            status=r["status"],
            last_check_at=r["last_check_at"],
            last_success_at=r["last_success_at"],
            last_error=r["last_error"],
            error_count=r["error_count"],
            # NULL until classified; stored as 0/1 → surface as bool|None.
            browser_playable=(None if r["browser_playable"] is None
                              else bool(r["browser_playable"])),
        )
        for r in conn.execute(sql)
    ]


def update_status(
    conn: sqlite3.Connection,
    *,
    channel_id: int,
    status: str,
    current_url: str | None,
    error: str | None,
    success: bool,
    browser_playable: bool | None = None,
) -> None:
    if status not in ("live", "unavailable", "unknown"):
        raise RegistryError(f"invalid_status: {status!r}")
    # Stored as 0/1/NULL. Only meaningful when live; a channel that goes
    # unavailable has its hint cleared to NULL so a stale "playable" never
    # lingers on an offline channel.
    bp = None if browser_playable is None else (1 if browser_playable else 0)
    now = "strftime('%Y-%m-%dT%H:%M:%fZ', 'now')"
    if success:
        conn.execute(
            f"UPDATE channels SET status=?, current_url=?, browser_playable=?, "
            f"last_check_at={now}, last_success_at={now}, last_error=NULL, "
            "error_count=0 WHERE id=?",
            (status, current_url, bp, channel_id),
        )
    else:
        conn.execute(
            f"UPDATE channels SET status=?, current_url=?, browser_playable=?, "
            f"last_check_at={now}, last_error=?, error_count=error_count+1 WHERE id=?",
            (status, current_url, bp, (error or "")[:200], channel_id),
        )


def seed_from_file(conn: sqlite3.Connection, path: Path) -> int:
    """Read a JSON file with shape `[{"slug","label","kind","source_url"}]`
    and upsert each entry. Returns the count seeded.

    Seed is idempotent (upsert), so re-running on every boot is fine — it's
    how operator-curated changes flow in without a redeploy step.
    """
    if not path.exists():
        log.info("seed_skip_no_file", extra={"path": str(path)})
        return 0
    try:
        payload = json.loads(path.read_text())
    except json.JSONDecodeError as e:
        raise RegistryError(f"seed_json_invalid: {e}") from None
    if not isinstance(payload, list):
        raise RegistryError("seed_root_not_list")
    n = 0
    for entry in payload:
        if not isinstance(entry, dict):
            continue
        try:
            upsert_channel(
                conn,
                slug=str(entry["slug"]),
                label=str(entry["label"]),
                source_url=str(entry["source_url"]),
                kind=str(entry.get("kind", "hls")),
            )
            n += 1
        except RegistryError as e:
            log.warning("seed_skip_invalid",
                        extra={"slug": entry.get("slug"), "reason": str(e)})
    return n
