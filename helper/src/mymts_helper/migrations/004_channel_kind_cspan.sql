-- 004 — admit kind='cspan' (free C-SPAN/.gov government-stream resolver).
--
-- Migration 003 widened the kind CHECK to ('hls','youtube'). This admits
-- 'cspan' for the free, no-login government floor feeds (the U.S. Senate floor;
-- see channels/cspan_resolver.py). The resolver reads the senate.gov floor
-- schedule JSON to mint the current per-session HLS master, which the prober
-- then validates with the SAME SSRF-safe master/variant fetch as any other
-- channel — so a cspan channel is never marked live until its resolved manifest
-- is actually reachable (a torn-down/not-in-session feed's variant 404s →
-- honest-offline). FREE content only — no auth, no DRM; the entitlement-gated
-- C-SPAN networks are not sourced.
--
-- SQLite can't ALTER a CHECK in place, so we recreate the table (the documented
-- 12-step lite procedure). channels has no inbound foreign keys, so the copy
-- preserves ids/state verbatim; the new schema is byte-identical to the 003
-- channels table except the kind CHECK now also admits 'cspan'.

CREATE TABLE channels_new (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    slug            TEXT NOT NULL UNIQUE,
    label           TEXT NOT NULL,
    kind            TEXT NOT NULL CHECK(kind IN ('hls', 'youtube', 'cspan')),
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
