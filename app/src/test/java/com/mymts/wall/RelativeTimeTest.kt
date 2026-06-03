package com.mymts.wall

import com.mymts.ui.wall.RelativeTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class RelativeTimeTest {

    private val now = Instant.parse("2026-06-03T01:00:00Z").toEpochMilli()
    private val clock = { now }

    @Test fun `null and blank input return null`() {
        assertNull(RelativeTime.render(null, clock))
        assertNull(RelativeTime.render("", clock))
        assertNull(RelativeTime.render("   ", clock))
    }

    @Test fun `unparseable input returns null`() {
        assertNull(RelativeTime.render("not-a-date", clock))
    }

    @Test fun `under a minute is 'now'`() {
        val recent = Instant.parse("2026-06-03T00:59:30Z").toString()
        assertEquals("now", RelativeTime.render(recent, clock))
    }

    @Test fun `minutes`() {
        val three = Instant.parse("2026-06-03T00:57:00Z").toString()
        assertEquals("3m", RelativeTime.render(three, clock))
    }

    @Test fun `hours`() {
        val two = Instant.parse("2026-06-02T23:00:00Z").toString()
        assertEquals("2h", RelativeTime.render(two, clock))
    }

    @Test fun `days`() {
        val three = Instant.parse("2026-05-31T01:00:00Z").toString()
        assertEquals("3d", RelativeTime.render(three, clock))
    }

    @Test fun `older than 30 days collapses to date`() {
        val old = "2025-12-01T01:00:00Z"
        assertEquals("2025-12-01", RelativeTime.render(old, clock))
    }

    @Test fun `slight future clock skew shows 'now', not negatives`() {
        val future = "2026-06-03T01:00:30Z"
        assertEquals("now", RelativeTime.render(future, clock))
    }
}
