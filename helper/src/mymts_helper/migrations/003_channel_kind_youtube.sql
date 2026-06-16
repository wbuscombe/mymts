-- 003 — admit kind='youtube' (yt-dlp YouTube-live resolver arrives).
--
-- Stage 2 shipped `kind TEXT NOT NULL CHECK(kind IN ('hls'))` and a code
-- comment promised the 'youtube' kind "lands in a later migration when
-- yt-dlp arrives." This is that migration. The helper now resolves a
-- YouTube /live URL to its HLS manifest via the in-process yt-dlp resolver
-- (see channels/youtube_resolver.py) and probes the resolved manifest with
-- the SAME SSRF-safe fetch + master/variant validation as a direct-HLS
-- channel — so a 'youtube' channel is never marked live until its resolved
-- manifest is actually reachable. The resolved manifest is googlevideo.com
-- (public https); the YouTube metadata fetch and the manifest fetch both
-- leave via the helper's normal (residential) egress — PIA is never touched.
--
-- SQLite cannot ALTER a CHECK constraint in place, so we recreate the table
-- (the documented 12-step lite procedure: new table → copy rows → drop old
-- → rename). channels has NO inbound foreign keys (feed_items references
-- sources, not channels), so no FK toggling is needed, and the copy
-- preserves ids/state verbatim. The new schema is byte-identical to the
-- 001+002 channels table except the kind CHECK now admits 'youtube' and the
-- browser_playable column (added additively by 002) is declared inline.

CREATE TABLE channels_new (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    slug            TEXT NOT NULL UNIQUE,
    label           TEXT NOT NULL,
    kind            TEXT NOT NULL CHECK(kind IN ('hls', 'youtube')),
    source_url      TEXT NOT NULL,
    enabled         INTEGER NOT NULL DEFAULT 1,
    current_url     TEXT,
    status          TEXT NOT NULL DEFAULT 'unknown'
        CHECK(status IN ('live', 'unavailable', 'unknown')),
    last_check_at   TEXT,
    last_success_at TEXT,
    last_error      TEXT,
    error_count     INTEGER NOT NULL DEFAULT 0,
    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    browser_playable INTEGER
);

INSERT INTO channels_new
    (id, slug, label, kind, source_url, enabled, current_url, status,
     last_check_at, last_success_at, last_error, error_count, created_at,
     browser_playable)
SELECT
    id, slug, label, kind, source_url, enabled, current_url, status,
    last_check_at, last_success_at, last_error, error_count, created_at,
    browser_playable
FROM channels;

DROP TABLE channels;

ALTER TABLE channels_new RENAME TO channels;
