package com.mymts

import com.mymts.player.StreamSpec
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

class StreamSpecTest {

    @Test
    fun httpsUrlIsAccepted() {
        val s = StreamSpec(id = "a", label = "A", url = "https://example.test/x.m3u8")
        assertEquals("a", s.id)
    }

    @Test
    fun httpUrlIsAccepted() {
        // Stage 1 still permits http for local-test fixtures; tightening to
        // https-only is a Stage 6 hardening decision tied to the network
        // security config + cleartext policy.
        val s = StreamSpec(id = "a", label = "A", url = "http://192.168.1.1/x.m3u8")
        assertEquals("a", s.id)
    }

    @Test
    fun rtspUrlIsRejected() {
        // The technical-approach hard boundary: no RTSP wiring on MyMTS.
        // Any RTSP URL crossing this validator is a sign someone copied
        // a sibling camera app's plumbing into the app.
        try {
            StreamSpec(id = "x", label = "X", url = "rtsp://192.168.1.1/stream")
            fail("expected IllegalArgumentException for rtsp scheme")
        } catch (_: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun fileUrlIsRejected() {
        try {
            StreamSpec(id = "x", label = "X", url = "file:///etc/passwd")
            fail("expected IllegalArgumentException for file scheme")
        } catch (_: IllegalArgumentException) {
            // ok
        }
    }
}
