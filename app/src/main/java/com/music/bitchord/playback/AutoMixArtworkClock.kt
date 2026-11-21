package com.music.bitchord.playback

/** Interpolates the outgoing end guard for artwork, without changing the audio fader clock. */
internal class AutoMixArtworkClock(
    private val halfTickNanos: Long,
    private val maxExtrapolationNanos: Long,
) {
    private var reportedRemainingMs: Long? = null
    private var seenAtNanos = 0L
    private var wasPlaying = false

    fun reset() {
        reportedRemainingMs = null
        wasPlaying = false
    }

    fun progress(
        incomingProgress: Float,
        faderProgress: Float,
        spanMs: Long,
        outgoingRemainingMs: Long?,
        outgoingPlaying: Boolean,
        outgoingSpeed: Float,
        nowNanos: Long,
        outgoingRanOn: Boolean,
    ): Float {
        // A late seek can leave the old deck unheld. Its next item's position no
        // longer describes this reveal, even if the incoming fader is still rising.
        if (faderProgress >= 1f || outgoingRanOn) return 1f
        if (outgoingRemainingMs == null) {
            reset()
            return incomingProgress
        }
        if (reportedRemainingMs != outgoingRemainingMs || wasPlaying != outgoingPlaying) {
            // Like the incoming clock, place a changed report halfway through the poll interval.
            // A new mix or resume has no preceding playing interval to interpolate from.
            seenAtNanos = if (wasPlaying && outgoingPlaying) nowNanos - halfTickNanos else nowNanos
            reportedRemainingMs = outgoingRemainingMs
            wasPlaying = outgoingPlaying
        }
        val elapsedMs = if (outgoingPlaying) {
            (nowNanos - seenAtNanos).coerceIn(0L, maxExtrapolationNanos) / 1e6 * outgoingSpeed
        } else {
            0.0
        }
        val outgoingProgress = ((spanMs - outgoingRemainingMs + elapsedMs) / spanMs).toFloat().coerceIn(0f, 1f)
        return maxOf(incomingProgress, outgoingProgress)
    }
}
