package com.mymts.data.helper

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HelperClientFeedParseTest {

    private val realResponse = """
      {
        "schema_version": 1,
        "items": [
          {
            "id": 42,
            "guid": "https://bbc.test/world/article-42",
            "source": "BBC World",
            "source_url": "https://feeds.bbci.co.uk/news/world/rss.xml",
            "title": "A headline of the moment",
            "summary": "Inert plain-text summary stripped of HTML.",
            "link": "https://bbc.test/world/article-42",
            "published_at": "2026-06-03T01:00:00.000Z",
            "fetched_at": "2026-06-03T01:00:42.000Z"
          },
          {
            "id": 43,
            "source": "Al Jazeera",
            "title": "Another headline",
            "summary": null,
            "link": null,
            "published_at": null,
            "fetched_at": "2026-06-03T01:01:00.000Z"
          }
        ]
      }
    """.trimIndent()

    @Test fun `parses two well-formed items with null-safe summary, link, published_at`() {
        val snap = HelperClient.parseFeed(JSONObject(realResponse))
        assertEquals(1, snap.schemaVersion)
        assertEquals(2, snap.items.size)

        val a = snap.items[0]
        assertEquals(42L, a.id)
        assertEquals("BBC World", a.source)
        assertEquals("A headline of the moment", a.title)
        assertEquals("Inert plain-text summary stripped of HTML.", a.summary)
        assertEquals("https://bbc.test/world/article-42", a.link)
        assertEquals("2026-06-03T01:00:00.000Z", a.publishedAtIso)

        val b = snap.items[1]
        assertNull(b.summary)
        assertNull(b.link)
        assertNull(b.publishedAtIso)
        assertEquals("2026-06-03T01:01:00.000Z", b.fetchedAtIso)
    }

    @Test fun `items without a title are silently skipped (helper contract floor)`() {
        val body = """
          {
            "schema_version": 1,
            "items": [
              {"id": 1, "source": "X", "title": "Has title"},
              {"id": 2, "source": "X"}
            ]
          }
        """.trimIndent()
        val snap = HelperClient.parseFeed(JSONObject(body))
        assertEquals(1, snap.items.size)
        assertEquals("Has title", snap.items.single().title)
    }

    @Test fun `unknown schema_version is refused`() {
        val body = """{"schema_version": 9, "items": []}"""
        try {
            HelperClient.parseFeed(JSONObject(body))
            fail("expected HelperException")
        } catch (e: HelperException) {
            assertTrue(e.message!!.contains("feed schema_version=9"))
        }
    }

    @Test fun `missing items array is refused (no silent empty)`() {
        val body = """{"schema_version": 1}"""
        try {
            HelperClient.parseFeed(JSONObject(body))
            fail("expected HelperException")
        } catch (e: HelperException) {
            assertTrue(e.message!!.contains("items"))
        }
    }
}
