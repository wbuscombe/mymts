package com.mymts.data.helper

import com.mymts.data.ticker.TickerEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for `HelperClient.parseTicker` — the TV's view of the
 * `/api/ticker/{markets,sports}` envelope. Pins real-vs-sample fidelity
 * (the TV must render exactly what the helper labelled) and
 * forward-compatible direction handling.
 */
class HelperClientTickerParseTest {

    private val marketsResponse = """
      {
        "schema_version": 1,
        "mode": "markets",
        "as_of": "2026-06-05T14:53:24.000Z",
        "stale": false,
        "entries": [
          {"symbol": "S&P 500", "display": "7,509.40", "direction": "down", "is_sample": false},
          {"symbol": "BTC",     "display": "$60,900",  "direction": "up",   "is_sample": false},
          {"symbol": "Brent",   "display": "73.42",    "direction": "flat", "is_sample": true}
        ]
      }
    """.trimIndent()

    @Test fun `parses markets envelope with mode + as_of + stale`() {
        val snap = HelperClient.parseTicker(JSONObject(marketsResponse))
        assertEquals("markets", snap.mode)
        assertEquals("2026-06-05T14:53:24.000Z", snap.asOfIso)
        assertFalse(snap.stale)
        assertEquals(3, snap.entries.size)
    }

    @Test fun `real-vs-sample flag travels per entry untouched`() {
        val snap = HelperClient.parseTicker(JSONObject(marketsResponse))
        val bySymbol = snap.entries.associateBy { it.symbol }
        assertFalse("S&P 500 is real", bySymbol.getValue("S&P 500").isSample)
        assertFalse("BTC is real", bySymbol.getValue("BTC").isSample)
        assertTrue("Brent stays sample", bySymbol.getValue("Brent").isSample)
    }

    @Test fun `direction tokens map correctly including none`() {
        val sports = """
          {"schema_version":1,"mode":"sports","as_of":null,"stale":false,
           "entries":[{"symbol":"MLB","display":"SD 4-6 PHI","direction":"none","is_sample":false}]}
        """.trimIndent()
        val snap = HelperClient.parseTicker(JSONObject(sports))
        assertEquals(TickerEntry.Direction.NONE, snap.entries[0].direction)
        assertNull(snap.asOfIso)
    }

    @Test fun `unknown direction token degrades to FLAT, does not throw`() {
        assertEquals(TickerEntry.Direction.FLAT, HelperClient.parseDirection("sideways"))
        assertEquals(TickerEntry.Direction.UP, HelperClient.parseDirection("UP"))
        assertEquals(TickerEntry.Direction.NONE, HelperClient.parseDirection("none"))
    }

    @Test(expected = HelperException::class)
    fun `wrong schema_version is rejected`() {
        HelperClient.parseTicker(JSONObject("""{"schema_version":2,"entries":[]}"""))
    }

    @Test fun `entry missing symbol or display is skipped, not fatal`() {
        val mixed = """
          {"schema_version":1,"mode":"markets","entries":[
            {"display":"no symbol","direction":"up","is_sample":false},
            {"symbol":"OK","display":"1.00","direction":"flat","is_sample":false}
          ]}
        """.trimIndent()
        val snap = HelperClient.parseTicker(JSONObject(mixed))
        assertEquals(1, snap.entries.size)
        assertEquals("OK", snap.entries[0].symbol)
    }

    @Test fun `missing is_sample defaults to true (fail safe toward honesty)`() {
        val noFlag = """
          {"schema_version":1,"mode":"markets","entries":[
            {"symbol":"X","display":"1","direction":"flat"}
          ]}
        """.trimIndent()
        val snap = HelperClient.parseTicker(JSONObject(noFlag))
        assertTrue("absent is_sample must default to sample, never live", snap.entries[0].isSample)
    }
}
