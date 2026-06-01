"""Tests for the RSS/Atom parser + HTML stripping.

The TV renders native text only, so the parser MUST hand back inert plain
text. These tests exercise the failure modes that would slip HTML through
otherwise (script tags, entities, mismatched/malformed markup).
"""

from __future__ import annotations

from mymts_helper.feeds.parser import parse, strip_html


def test_strip_html_removes_tags() -> None:
    assert strip_html("<p>hello <b>world</b></p>") == "hello world"


def test_strip_html_drops_script_content() -> None:
    out = strip_html("<p>before</p><script>alert(1)</script><p>after</p>")
    assert "alert" not in out
    assert "script" not in out.lower()
    assert "before" in out and "after" in out


def test_strip_html_drops_style_content() -> None:
    out = strip_html("<style>body { background: red }</style>OK")
    assert "background" not in out
    assert "OK" in out


def test_strip_html_decodes_entities() -> None:
    # Plain ampersand decodes to text. But &lt;c&gt; decodes to "<c>" which
    # the stripper then treats as an HTML tag and removes — that's the
    # safer behavior (a source can't slip a tag past us by entity-encoding
    # it). Both effects are exercised here.
    assert strip_html("a &amp; b") == "a & b"
    assert strip_html("a &amp; b &lt;c&gt;") == "a & b"


def test_strip_html_collapses_whitespace() -> None:
    assert strip_html("a   \n  b\t\tc") == "a b c"


def test_strip_html_empty_and_none() -> None:
    assert strip_html("") == ""
    assert strip_html(None) == ""


def test_strip_html_falls_back_on_garbage() -> None:
    # Highly malformed input — fallback regex should still produce something.
    out = strip_html("<<<<a>>>hello</<<<>")
    assert "hello" in out


RSS_SAMPLE = b"""<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0"><channel>
<title>Sample</title>
<item>
  <title>First headline</title>
  <link>https://example.test/a</link>
  <guid isPermaLink="true">https://example.test/a</guid>
  <description>&lt;p&gt;Body of <b>first</b> item.&lt;/p&gt;</description>
  <pubDate>Sun, 01 Jun 2026 12:00:00 GMT</pubDate>
</item>
<item>
  <title>Second &amp; more</title>
  <link>https://example.test/b</link>
  <guid>id-b</guid>
  <description>Plain text.</description>
  <pubDate>Sun, 01 Jun 2026 12:05:00 GMT</pubDate>
</item>
</channel></rss>
"""


def test_parse_rss_basic() -> None:
    r = parse(RSS_SAMPLE, source_url="https://example.test/feed.xml")
    assert len(r.items) == 2
    first = r.items[0]
    assert first.title == "First headline"
    assert first.guid == "https://example.test/a"
    assert "Body of first item" in first.summary
    assert "<b>" not in first.summary
    assert first.link == "https://example.test/a"
    assert first.published_at is not None
    assert first.published_at.startswith("2026-06-01T12:00:00")


def test_parse_drops_items_without_title() -> None:
    body = b"""<?xml version="1.0"?><rss version="2.0"><channel>
    <title>x</title>
    <item><link>https://x/</link><guid>g</guid></item>
    </channel></rss>"""
    r = parse(body, source_url="https://x/")
    assert r.items == []


def test_parse_synthesizes_guid_when_missing_id_and_link() -> None:
    body = b"""<?xml version="1.0"?><rss version="2.0"><channel>
    <title>x</title>
    <item><title>Just a title</title></item>
    </channel></rss>"""
    r = parse(body, source_url="https://x/")
    assert len(r.items) == 1
    assert r.items[0].guid.startswith("sha256:")


def test_parse_rejects_non_http_link() -> None:
    body = b"""<?xml version="1.0"?><rss version="2.0"><channel>
    <title>x</title>
    <item><title>T</title><link>javascript:alert(1)</link><guid>g</guid></item>
    </channel></rss>"""
    r = parse(body, source_url="https://x/")
    assert len(r.items) == 1
    assert r.items[0].link == ""


def test_parse_strips_html_in_title() -> None:
    body = b"""<?xml version="1.0"?><rss version="2.0"><channel>
    <title>x</title>
    <item><title>Hot &lt;script&gt;alert()&lt;/script&gt; news</title>
    <guid>g</guid></item>
    </channel></rss>"""
    r = parse(body, source_url="https://x/")
    assert len(r.items) == 1
    assert "<script" not in r.items[0].title.lower()
    assert "alert" not in r.items[0].title


def test_parse_handles_malformed_xml() -> None:
    # Truncated mid-tag — parser must not raise.
    r = parse(b"<?xml ver", source_url="https://x/")
    assert r.bozo  # parser flags it
    assert r.items == []


ATOM_SAMPLE = b"""<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <title>Atom Sample</title>
  <entry>
    <id>tag:example.test,2026:atom-1</id>
    <title>Atom item</title>
    <link href="https://example.test/atom-1" />
    <updated>2026-06-01T08:30:00Z</updated>
    <summary type="html">&lt;p&gt;Summary here&lt;/p&gt;</summary>
  </entry>
</feed>
"""


def test_parse_atom() -> None:
    r = parse(ATOM_SAMPLE, source_url="https://example.test/atom")
    assert len(r.items) == 1
    it = r.items[0]
    assert it.title == "Atom item"
    assert it.guid == "tag:example.test,2026:atom-1"
    assert "Summary here" in it.summary
    assert it.link == "https://example.test/atom-1"
    assert it.published_at is not None
