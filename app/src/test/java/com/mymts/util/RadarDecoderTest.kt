package com.mymts.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the decoder-selection threshold behind the 2026-07 radar crash hardening:
 * API 28+ (the Onn box is 34) uses the platform ImageDecoder path, retiring the
 * deprecated Movie-based GifDecoder; only the minSdk-23 floor keeps the legacy path.
 */
class RadarDecoderTest {

    @Test fun `API 28+ uses the platform ImageDecoder path`() {
        assertTrue(RadarDecoder.preferAnimatedImageDecoder(28))  // P — the threshold
        assertTrue(RadarDecoder.preferAnimatedImageDecoder(34))  // the actual Onn box
        assertTrue(RadarDecoder.preferAnimatedImageDecoder(35))  // targetSdk
    }

    @Test fun `below API 28 falls back to the legacy Movie GifDecoder`() {
        assertFalse(RadarDecoder.preferAnimatedImageDecoder(27))
        assertFalse(RadarDecoder.preferAnimatedImageDecoder(23))  // minSdk floor
    }
}
