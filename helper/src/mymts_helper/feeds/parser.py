"""Defensive RSS/Atom parser.

The helper's whole reason to exist is concentrating hostile-input handling
in one sandboxed place (Trust Bar A1). Every field that comes back to the
TV is plain text — the TV renders native text widgets, not HTML — so this
parser strips all HTML and decodes all entities before storing.

We use feedparser as the format-tolerant frontend. feedparser uses
defusedxml automatically when it's importable (we declare it as a runtime
dependency for exactly this reason), so XXE / entity-bomb / external-DTD
attacks are neutralised before our code sees the parsed tree.
"""

from __future__ import annotations

import hashlib
import html
import logging
import re
from dataclasses import dataclass
from datetime import UTC, datetime
from html.parser import HTMLParser

import feedparser

log = logging.getLogger("mymts_helper.feeds.parser")

MAX_TITLE_LEN = 500
MAX_SUMMARY_LEN = 4000
MAX_LINK_LEN = 2000


class _StripHTML(HTMLParser):
    """Subset of html.parser that emits only text. Tags are dropped entirely,
    not preserved as text. Script/style content is dropped along with tags.
    """

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self._chunks: list[str] = []
        self._skip_depth = 0

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag in {"script", "style", "iframe", "object", "embed"}:
            self._skip_depth += 1

    def handle_endtag(self, tag: str) -> None:
        if tag in {"script", "style", "iframe", "object", "embed"} and self._skip_depth:
            self._skip_depth -= 1

    def handle_data(self, data: str) -> None:
        if self._skip_depth:
            return
        self._chunks.append(data)

    def text(self) -> str:
        return "".join(self._chunks)


def strip_html(s: str | None) -> str:
    """Return a plain-text version of `s` with HTML tags and entities removed.

    Idempotent. Safe to call on text that contains no HTML. Final whitespace
    is collapsed to single spaces because the TV will line-wrap on its own.
    """
    if not s:
        return ""
    # Decode HTML entities up-front so the stripper sees the underlying text.
    decoded = html.unescape(s)
    stripper = _StripHTML()
    try:
        stripper.feed(decoded)
        stripper.close()
    except Exception:
        # Malformed HTML — fall back to a regex strip.
        decoded = re.sub(r"<[^>]+>", "", decoded)
        return re.sub(r"\s+", " ", decoded).strip()
    out = stripper.text()
    return re.sub(r"\s+", " ", out).strip()


def _truncate(s: str, n: int) -> str:
    if len(s) <= n:
        return s
    return s[: n - 1].rstrip() + "…"


def _to_utc_iso(struct_time) -> str | None:
    if struct_time is None:
        return None
    try:
        # feedparser hands back a time.struct_time in UTC for parsed dates.
        dt = datetime(*struct_time[:6], tzinfo=UTC)
        return dt.strftime("%Y-%m-%dT%H:%M:%S.000Z")
    except (TypeError, ValueError):
        return None


@dataclass(frozen=True)
class ParsedItem:
    guid: str
    title: str
    summary: str
    link: str
    published_at: str | None


@dataclass(frozen=True)
class ParseResult:
    bozo: bool          # feedparser's flag for "could not parse cleanly"
    bozo_reason: str    # short summary of why, if bozo
    items: list[ParsedItem]


def parse(body: bytes, *, source_url: str) -> ParseResult:
    """Parse an RSS/Atom/JSON-feed body. Never raises.

    source_url is used as a stable fallback when an entry has no id/link
    (we hash it with title + published_at).
    """
    fp = feedparser.parse(body)
    bozo_reason = ""
    bozo = bool(getattr(fp, "bozo", False))
    if bozo:
        exc = getattr(fp, "bozo_exception", None)
        bozo_reason = type(exc).__name__ if exc else "unknown"

    items: list[ParsedItem] = []
    for entry in fp.entries:
        title = strip_html(entry.get("title") or "")
        if not title:
            # Title is the one field we won't synthesize. A feed item without
            # a title is unhelpful to the TV.
            continue
        title = _truncate(title, MAX_TITLE_LEN)

        # summary: prefer 'summary', then 'description', then content[0].value.
        raw_summary = entry.get("summary") or entry.get("description") or ""
        if not raw_summary:
            content = entry.get("content")
            if content and isinstance(content, list) and content:
                raw_summary = content[0].get("value", "") or ""
        summary = _truncate(strip_html(raw_summary), MAX_SUMMARY_LEN)

        link = (entry.get("link") or "").strip()
        link = _truncate(link, MAX_LINK_LEN) if link.startswith(("http://", "https://")) else ""

        published_at = _to_utc_iso(entry.get("published_parsed"))
        if not published_at:
            published_at = _to_utc_iso(entry.get("updated_parsed"))

        # GUID: id first, then link, then a stable hash of source+title+published.
        guid = (entry.get("id") or "").strip()
        if not guid:
            guid = link
        if not guid:
            h = hashlib.sha256()
            h.update(source_url.encode("utf-8", "replace"))
            h.update(b"\x00")
            h.update(title.encode("utf-8", "replace"))
            h.update(b"\x00")
            h.update((published_at or "").encode("utf-8", "replace"))
            guid = "sha256:" + h.hexdigest()[:32]

        items.append(
            ParsedItem(
                guid=guid,
                title=title,
                summary=summary,
                link=link,
                published_at=published_at,
            )
        )

    return ParseResult(bozo=bozo, bozo_reason=bozo_reason, items=items)
