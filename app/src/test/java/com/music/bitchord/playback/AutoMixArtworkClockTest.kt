package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoMixArtworkClockTest {
    private val clock = AutoMixArtworkClock(halfTickNanos = 15_000_000L, maxExtrapolationNanos = 600_000_000L)

    private fun sample(
        timeMs: Long,
        remainingMs: Long?,
        incoming: Float = 0.3f,
        fader: Float = maxOf(incoming, remainingMs?.let { (6_000L - it) / 6_000f } ?: 0f),
        playing: Boolean = true,
        speed: Float = 1f,
        ranOn: Boolean = false,
    ): Float = clock.progress(incoming, fader, 6_000L, remainingMs, playing, speed, timeMs * 1_000_000L, ranOn)

    @Test
    fun `end-bound artwork advances between reports instead of jumping with them`() {
        // The end guard wins despite a moving incoming clock, as in the 505 capture.
        val first = sample(0, 2_880)
        val intermediate = sample(120, 2_880, incoming = 0.36f)
        val beforeReport = sample(240, 2_880, incoming = 0.41f)
        val nextReport = sample(270, 2_620, incoming = 0.42f)
        assertEquals(0.52f, first, 0.00001f)
        assertEquals(0.54f, intermediate, 0.00001f)
        assertEquals(0.56f, beforeReport, 0.00001f)
        assertTrue(nextReport - beforeReport in 0f..0.01f)
    }

    @Test
    fun `overlaps before the outgoing end still use incoming progress`() {
        assertEquals(0.4f, sample(0, 13_400, incoming = 0.4f), 0f)
        assertEquals(0.45f, sample(270, 13_400, incoming = 0.45f), 0f)
    }

    @Test
    fun `reset for a late seek uses the new report without inheriting elapsed time`() {
        sample(0, 2_880)
        sample(250, 2_880)
        clock.reset()
        assertEquals(0.7f, sample(1_000, 1_800), 0.00001f)
        assertEquals(0.71f, sample(1_060, 1_800), 0.00001f)
    }

    @Test
    fun `paused outgoing samples do not extrapolate or count paused time on resume`() {
        sample(0, 2_880)
        assertEquals(0.52f, sample(100, 2_880, playing = false), 0.00001f)
        assertEquals(0.52f, sample(2_000, 2_880, playing = false), 0.00001f)
        assertEquals(0.52f, sample(2_100, 2_880), 0.00001f)
        assertEquals(0.53f, sample(2_160, 2_880), 0.00001f)
    }

    @Test
    fun `extrapolation follows outgoing tempo but stops after the prediction limit`() {
        sample(0, 2_880, speed = 1.2f)
        assertEquals(0.64f, sample(3_000, 2_880, speed = 1.2f), 0.00001f)
    }

    @Test
    fun `missing outgoing position falls back to incoming and audio completion takes priority`() {
        sample(0, 2_880)
        assertEquals(0.55f, sample(250, null, incoming = 0.55f), 0f)
        assertEquals(1f, sample(280, null, incoming = 0.6f, fader = 1f), 0f)
    }

    @Test
    fun `seeking too late to hold the original item still settles artwork when the deck runs on`() {
        // The raw position now describes another track while the unchanged faders catch up.
        assertTrue(sample(0, 9, incoming = 0.8f) < 1f)
        assertEquals(1f, sample(31, 252_904, incoming = 0.81f, ranOn = true), 0f)
        assertEquals(1f, sample(62, 252_873, incoming = 0.82f, ranOn = true), 0f)
    }
}
