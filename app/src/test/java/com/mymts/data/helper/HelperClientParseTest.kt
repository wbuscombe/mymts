package com.mymts.data.helper

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HelperClientParseTest {

    private val realResponse = """
      {
        "schema_version": 1,
        "channels": [
          {
            "slug": "dw-news-en",
            "label": "DW News English",
            "kind": "hls",
            "current_url": "https://dw.example/index.m3u8",
            "status": "live",
            "enabled": true,
            "last_check_at": "2026-06-03T00:49:29Z",
            "last_success_at": "2026-06-03T00:49:29Z",
            "last_error": null,
            "error_count": 0
          },
          {
            "slug": "al-jazeera-en",
            "label": "Al Jazeera English",
            "kind": "hls",
            "current_url": null,
            "status": "unavailable",
            "enabled": true,
            "last_check_at": "2026-06-03T00:49:24Z",
            "last_success_at": null,
            "last_error": "fetch:dns_failure",
            "error_count": 48
          }
        ]
      }
    """.trimIndent()

    @Test fun `parses live + unavailable rows and masks current_url when not live`() {
        val snap = HelperClient.parseChannels(JSONObject(realResponse))
        assertEquals(1, snap.schemaVersion)
        assertEquals(2, snap.channels.size)

        val dw = snap.channels.first { it.slug == "dw-news-en" }
        assertEquals(Channel.Status.LIVE, dw.status)
        assertEquals("https://dw.example/index.m3u8", dw.currentUrl)
        assertTrue(dw.isPlayable)

        val aj = snap.channels.first { it.slug == "al-jazeera-en" }
        assertEquals(Channel.Status.UNAVAILABLE, aj.status)
        assertNull("non-live channel must surface a null current_url (C3)", aj.currentUrl)
        assertFalse(aj.isPlayable)
        assertEquals(48, aj.errorCount)
    }

    @Test fun `playable subset yields only LIVE channels with a current_url`() {
        val snap = HelperClient.parseChannels(JSONObject(realResponse))
        val playable = snap.playable
        assertEquals(1, playable.size)
        assertEquals("dw-news-en", playable.single().slug)
    }

    @Test fun `unknown schema_version is refused`() {
        val body = """{"schema_version": 2, "channels": []}"""
        try {
            HelperClient.parseChannels(JSONObject(body))
            fail("expected HelperException")
        } catch (e: HelperException) {
            assertTrue(e.message!!.contains("schema_version=2"))
        }
    }

    @Test fun `missing channels array is refused (no silent empty)`() {
        val body = """{"schema_version": 1}"""
        try {
            HelperClient.parseChannels(JSONObject(body))
            fail("expected HelperException")
        } catch (e: HelperException) {
            assertTrue(e.message!!.contains("channels"))
        }
    }

    @Test fun `unknown status string maps to UNKNOWN, not LIVE`() {
        val body = """
          {
            "schema_version": 1,
            "channels": [
              {"slug":"x","label":"X","kind":"hls","current_url":"https://x/","status":"who-knows"}
            ]
          }
        """.trimIndent()
        val snap = HelperClient.parseChannels(JSONObject(body))
        val x = snap.channels.single()
        assertEquals(Channel.Status.UNKNOWN, x.status)
        // Even with a url present, a non-LIVE channel must not be playable.
        assertFalse(x.isPlayable)
    }

    @Test fun `current_url blank string treated as null`() {
        val body = """
          {
            "schema_version": 1,
            "channels": [
              {"slug":"x","label":"X","kind":"hls","current_url":"","status":"live"}
            ]
          }
        """.trimIndent()
        val snap = HelperClient.parseChannels(JSONObject(body))
        val x = snap.channels.single()
        assertNull(x.currentUrl)
        assertFalse(x.isPlayable)
    }
}
