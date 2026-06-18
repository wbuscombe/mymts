package com.mymts.data.helper

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parse guards for `/api/presets` (server-authoritative wall presets).
 * The wall renders whatever the helper serves, so the parser must round-trip
 * the served shape faithfully: ordered slugs, the fill mode, and the optional
 * grid block (nullable dims when absent).
 */
class HelperClientPresetParseTest {

    private val realResponse = """
      {
        "schema_version": 1,
        "default": "news",
        "presets": [
          {
            "id": "news",
            "name": "News Wall",
            "slugs": ["livenow-fox", "fox-weather", "bbc-news", "cbs-sports-hq"],
            "fill": "topup",
            "grid": { "rows": 2, "cols": 2 }
          },
          {
            "id": "space",
            "name": "Space",
            "slugs": ["iss-feed", "nasa-tv"],
            "fill": "exact",
            "grid": { "rows": 1, "cols": 2 }
          }
        ]
      }
    """.trimIndent()

    @Test fun `parses default id and every served preset in order`() {
        val snap = HelperClient.parsePresets(JSONObject(realResponse))
        assertEquals(1, snap.schemaVersion)
        assertEquals("news", snap.default)
        assertEquals(listOf("news", "space"), snap.presets.map { it.id })
    }

    @Test fun `news preset round-trips slugs, fill and grid`() {
        val news = HelperClient.parsePresets(JSONObject(realResponse)).presets.first { it.id == "news" }
        assertEquals("News Wall", news.name)
        assertEquals("topup", news.fill)
        assertEquals(listOf("livenow-fox", "fox-weather", "bbc-news", "cbs-sports-hq"), news.slugs)
        assertEquals(2, news.gridRows)
        assertEquals(2, news.gridCols)
    }

    @Test fun `exact preset keeps its fill mode`() {
        val space = HelperClient.parsePresets(JSONObject(realResponse)).presets.first { it.id == "space" }
        assertEquals("exact", space.fill)
        assertEquals(listOf("iss-feed", "nasa-tv"), space.slugs)
    }

    @Test fun `absent grid block yields null dims (no fabricated grid)`() {
        val body = """
          {
            "schema_version": 1,
            "default": "news",
            "presets": [
              {"id": "chill", "name": "Chill", "slugs": ["a", "b"], "fill": "exact"}
            ]
          }
        """.trimIndent()
        val chill = HelperClient.parsePresets(JSONObject(body)).presets.single()
        assertNull(chill.gridRows)
        assertNull(chill.gridCols)
    }

    @Test fun `name defaults to id when the helper omits it`() {
        val body = """
          {
            "schema_version": 1,
            "default": "news",
            "presets": [ {"id": "nature", "slugs": [], "fill": "exact"} ]
          }
        """.trimIndent()
        val nature = HelperClient.parsePresets(JSONObject(body)).presets.single()
        assertEquals("nature", nature.name)
        assertTrue(nature.slugs.isEmpty())
    }

    @Test fun `unknown schema_version is refused (no silent parse of a changed contract)`() {
        val body = """{"schema_version": 9, "default": "news", "presets": []}"""
        try {
            HelperClient.parsePresets(JSONObject(body))
            throw AssertionError("expected HelperException")
        } catch (e: HelperException) {
            assertTrue(e.message!!.contains("schema_version=9"))
        }
    }

    @Test fun `missing presets array is refused (no silent empty)`() {
        val body = """{"schema_version": 1, "default": "news"}"""
        try {
            HelperClient.parsePresets(JSONObject(body))
            throw AssertionError("expected HelperException")
        } catch (e: HelperException) {
            assertTrue(e.message!!.contains("presets"))
        }
    }
}
