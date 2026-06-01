-- 001 — initial schema (Stage 2 Part A).
--
-- sources       — registered RSS/Atom feeds. The operator owns content;
--                 the helper just stores their registration + fetch state.
-- feed_items    — normalized, sanitized news items. summary is plain text
--                 (HTML stripped) — the TV renders native text.
-- channels      — registered live channels. v1 stores HLS URLs only;
--                 yt-dlp-resolved kinds land in a later migration.
-- meta          — k/v for schema_version + future bookkeeping.
--
-- All timestamps are ISO 8601 UTC text. SQLite has no native timezone-
-- aware type; storing UTC strings is the standard pattern and survives
-- backup/restore without TZ drift.

CREATE TABLE sources (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    url             TEXT NOT NULL UNIQUE,
    label           TEXT NOT NULL,
    enabled         INTEGER NOT NULL DEFAULT 1,
    last_fetch_at   TEXT,
    last_success_at TEXT,
    last_error      TEXT,
    error_count     INTEGER NOT NULL DEFAULT 0,
    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE TABLE feed_items (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    source_id    INTEGER NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
    guid         TEXT NOT NULL,
    title        TEXT NOT NULL,
    summary      TEXT,
    link         TEXT,
    published_at TEXT,
    fetched_at   TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    UNIQUE(source_id, guid)
);

CREATE INDEX idx_feed_items_fetched_desc ON feed_items(fetched_at DESC);
CREATE INDEX idx_feed_items_published_desc ON feed_items(published_at DESC);

CREATE TABLE channels (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    slug            TEXT NOT NULL UNIQUE,
    label           TEXT NOT NULL,
    kind            TEXT NOT NULL CHECK(kind IN ('hls')),
    source_url      TEXT NOT NULL,
    enabled         INTEGER NOT NULL DEFAULT 1,
    current_url     TEXT,
    status          TEXT NOT NULL DEFAULT 'unknown'
        CHECK(status IN ('live', 'unavailable', 'unknown')),
    last_check_at   TEXT,
    last_success_at TEXT,
    last_error      TEXT,
    error_count     INTEGER NOT NULL DEFAULT 0,
    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE TABLE meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
