"""Boot-time feed-source seed loader.

Mirrors `channels.registry.seed_from_file`: reads `feeds/seed.json` at
helper startup and upserts each `{url, label}` pair via
`store.upsert_source`. Operator-curated edits to the seed file flow
through every restart; the upsert is idempotent so no duplicates pile
up.

Seed entries are a starting set of known-good general-news RSS feeds
so the feed pane has real content to render at Stage 3 launch. The
operator can edit the seed file (and rebuild the image) to refine the
list; per-source enable/disable + add-your-own UI is a later stage.
"""

from __future__ import annotations

import json
import logging
import sqlite3
from pathlib import Path

from . import store

log = logging.getLogger("mymts_helper.feeds.seeder")


class SeederError(Exception):
    """Seed file is unreadable or malformed."""


def seed_from_file(conn: sqlite3.Connection, path: Path) -> int:
    """Upsert each source in the JSON file. Returns the count seeded.

    Missing file is a soft no-op (logs and returns 0); malformed JSON or
    invalid rows raise so the operator notices on the first boot.
    """
    if not path.exists():
        log.info("feed_seed_skip_no_file", extra={"path": str(path)})
        return 0
    try:
        entries = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        raise SeederError(f"feed_seed_invalid_json: {e}") from None
    if not isinstance(entries, list):
        raise SeederError("feed_seed_root_not_list")

    seeded = 0
    for i, entry in enumerate(entries):
        if not isinstance(entry, dict):
            raise SeederError(f"feed_seed_entry_{i}_not_object")
        url = entry.get("url")
        label = entry.get("label")
        if not isinstance(url, str) or not url:
            log.warning("feed_seed_skip_invalid_url", extra={"index": i})
            continue
        if not isinstance(label, str) or not label:
            log.warning("feed_seed_skip_invalid_label", extra={"index": i, "url": url})
            continue
        try:
            store.upsert_source(conn, url=url, label=label)
        except sqlite3.DatabaseError as e:
            log.warning(
                "feed_seed_skip_db_error",
                extra={"index": i, "url": url, "error": str(e)},
            )
            continue
        seeded += 1

    conn.commit()
    log.info("feed_sources_seeded", extra={"count": seeded})
    return seeded
