package com.music.bitchord.ui.player

import androidx.compose.ui.graphics.Color
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasSource
import com.music.bitchord.data.model.QueueEntryIdentity
import com.music.bitchord.playback.AutoMixArtworkTuning
import com.music.bitchord.playback.AutoMixArtworkWindow
import com.music.bitchord.playback.AutoMixVisualPhase
import com.music.bitchord.playback.AutoMixVisualState
import com.music.bitchord.playback.AutoMixVisualTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AutoMixCanvasTest {
    private val outgoing = QueueEntryIdentity("a", null, 0)
    private val incoming = QueueEntryIdentity("b", null, 0)
    private val clip = CanvasArtwork("incoming.mp4")
    private val visual = AutoMixVisualState(
        1L,
        AutoMixVisualTrack(outgoing, 0, null),
        AutoMixVisualTrack(incoming, 1, null),
    )

    @Test
    fun disablingArtworkRejectsTheHeldGenerationButNotTheNextMix() {
        val owner = AutoMixCanvasPair()
        val tracks = requireNotNull(owner.start(visual, outgoing))
        tracks.incomingClip = clip
        tracks.started = true
        assertTrue(tracks.usesGestureSleeve(gesturing = true))
        val disabled = AutoMixArtworkGate(enabled = false, skipThrough = visual.generation)
        assertFalse(disabled.allows(tracks.generation))
        owner.clear()
        assertNull(owner.visual)
        val enabled = disabled.copy(enabled = true)
        val remounted = AutoMixCanvasPair()
        assertNull(remounted.start(visual, outgoing, enabled))
        assertTrue(remounted.start(visual.copy(generation = visual.generation + 1), outgoing, enabled) != null)
    }

    @Test
    fun onlyStartedMixedArtworkUsesTheMovingSleeve() {
        val tracks = AutoMixCanvasTracks(1L, outgoing, incoming, clip) { visual }
        assertFalse(tracks.usesGestureSleeve(gesturing = true))
        tracks.started = true
        assertTrue(tracks.usesGestureSleeve(gesturing = true))
        assertFalse(tracks.usesGestureSleeve(gesturing = false))
        tracks.pinnedClip = null
        assertFalse(tracks.usesGestureSleeve(gesturing = true))
        tracks.incomingClip = clip
        assertTrue(tracks.usesGestureSleeve(gesturing = true))
    }

    @Test
    fun outgoingArtworkStaysOpaqueUntilOnlyTheUncoveredTailNeedsRetiring() {
        assertEquals(1f, outgoingArtworkTailAlpha(0f))
        assertEquals(1f, outgoingArtworkTailAlpha(0.5f))
        assertEquals(1f, outgoingArtworkTailAlpha(0.8f))
        val tail = (80..100).map { outgoingArtworkTailAlpha(it / 100f) }
        assertTrue(tail.zipWithNext().all { (a, b) -> a >= b })
        assertTrue(tail[10] in 0.4f..0.6f)
        assertEquals(0f, tail.last())
    }

    @Test
    fun onlyMatchingHeroSlotsCanUseTheLiveReveal() {
        assertTrue(sharesHeroRevealFootprint(true, true, 0f, 0f))
        assertTrue(sharesHeroRevealFootprint(false, false, 1f, 16f / 9f))
        assertFalse(sharesHeroRevealFootprint(true, false, 1f, 1f))
        assertFalse(sharesHeroRevealFootprint(false, false, 1f, 0.56f))
        assertTrue(sharesHeroRevealFootprint(false, false, 0.56f, 0.56f))
        assertFalse(sharesHeroRevealFootprint(false, false, 0.56f, 0.63f))
        assertFalse(sharesHeroRevealFootprint(false, false, 1f, 0f))
    }

    @Test
    fun spatialAndOpacityStandbyShareTheSameClockAndGeneration() {
        val tracks = AutoMixCanvasTracks(42L, outgoing, incoming, null) { visual.copy(generation = 42L) }
        val spatial = tracks.standbyReveal(allowSpatialMask = true)
        val fallback = tracks.standbyReveal(allowSpatialMask = false)
        assertEquals(42L, spatial.generation)
        assertEquals(spatial.generation, fallback.generation)
        assertSame(spatial.progress, fallback.progress)
        assertTrue(spatial.allowSpatialMask)
        assertFalse(fallback.allowSpatialMask)
        assertEquals(0f, spatial.progress())
        tracks.incomingClip = clip
        tracks.reportIncoming(true)
        tracks.sealIncoming()
        assertEquals(0f, fallback.progress(), message = "a ready frame is still hidden before the window")
    }

    @Test
    fun revealDrawUsesTheCurrentAudioSampleEvenWhenTheCollectorIsBehind() {
        val window = AutoMixArtworkWindow(1.0, 3.0)
        var sample = visual.copy(phase = AutoMixVisualPhase.MIXING, artworkWindow = window)
        val tracks = AutoMixCanvasTracks(visual.generation, outgoing, incoming, null) { sample }
        tracks.windowOpen = true
        tracks.incomingClip = clip
        tracks.reportIncoming(true)
        assertTrue(tracks.sealIncoming())
        tracks.progress = 0.25f
        sample = sample.copy(audioSeconds = 2.0, artworkProgress = 0.5f)
        assertEquals(AutoMixArtworkTuning.revealProgress(0.5f), tracks.standbyOpacity())

        sample = sample.copy(phase = AutoMixVisualPhase.CANCELLED, artworkWindow = null, artworkProgress = 0f)
        assertEquals(0f, tracks.standbyOpacity())
        sample = visual.copy(generation = visual.generation + 1, artworkWindow = window, artworkProgress = 0.75f)
        assertEquals(0f, tracks.standbyOpacity())
    }

    @Test
    fun readyIncomingTransfersWithoutReplacingItsClip() {
        val owner = AutoMixCanvasPair()
        val tracks = requireNotNull(owner.start(visual, outgoing))
        tracks.incomingClip = clip
        tracks.incomingReady = true
        assertTrue(tracks.sealIncoming())
        owner.finish(tracks)
        assertNull(owner.visual)
        assertSame(tracks, owner.promoted)
        assertSame(clip, owner.promoted?.incomingClip)
    }

    @Test
    fun incomingFrameColorsAreTheSameAtRevealAndPromotion() {
        val owner = AutoMixCanvasPair()
        val tracks = AutoMixCanvasTracks(visual.generation, outgoing, incoming, null) { visual }
        tracks.incomingClip = clip
        tracks.reportIncoming(true)
        val palette = MeshPalette(listOf(Color.Red))
        tracks.retainIncomingAmbient(clip, AutoMixArtworkGate(), palette = palette)
        assertNull(tracks.incomingAmbient, "a prepared frame must not bypass the live/still choice")
        assertTrue(tracks.sealIncoming())
        val revealTarget = requireNotNull(tracks.incomingAmbient)
        assertSame(palette, revealTarget.palette)
        assertEquals(visual.generation, revealTarget.generation)
        assertEquals(incoming, revealTarget.entry)
        assertEquals(0f, revealTarget.alphaAt(0f))
        assertEquals(1f, revealTarget.alphaAt(1f))
        owner.finish(tracks)
        assertSame(revealTarget, owner.promoted?.incomingAmbient)
        val settled = AutoMixAmbient(settled = tracks.generation to tracks.incoming)
        assertSame(revealTarget, settled.settledCanvas(owner.promoted?.incomingAmbient))
        val blur = AutoMixAmbient()
        val mesh = blur.withCanvasHandoff(owner.promoted?.incomingAmbient, promoted = true)
        assertNull(blur.settled, "Canvas settling must not reset the still-only blur")
        assertSame(revealTarget, mesh.settledCanvas(revealTarget))
        assertNull(AutoMixAmbient(cancelled = true, settled = settled.settled).settledCanvas(revealTarget))
        assertNull(AutoMixAmbient(settled = (tracks.generation + 1) to tracks.incoming).settledCanvas(revealTarget))
        owner.displayed(incoming.copy(occurrence = 1), clip, true)
        assertNull(owner.promoted, "a different occurrence of the same song cannot inherit the seed")
    }

    @Test
    fun acceptedCanvasCanSettleAtWindowEndBeforeNativePromotion() {
        val sample = AutoMixCanvasAmbient(visual.generation, incoming, clip, null, MeshPalette(listOf(Color.Red)), 0f)
        val still = AutoMixAmbient(canvasBoundary = visual.generation to incoming)
        assertNull(still.settled, "the blur cannot inherit a mesh-only epoch")
        val settled = still.withCanvasHandoff(sample, promoted = false)
        assertSame(sample, settled.settledCanvas(sample))
        assertSame(still, still.withCanvasHandoff(sample.copy(entry = outgoing), promoted = false))
        assertSame(still, still.withCanvasHandoff(sample.copy(generation = visual.generation + 1L), promoted = false))
        val beforeEnd = AutoMixAmbient()
        assertSame(beforeEnd, beforeEnd.withCanvasHandoff(sample, promoted = false))
    }

    @Test
    fun lateOrIneligibleFrameColorsDoNotReplaceTheRevealTarget() {
        val window = AutoMixArtworkWindow(1.0, 3.0)
        val palette = MeshPalette(listOf(Color.Red))
        var sample = visual
        val tracks = AutoMixCanvasTracks(visual.generation, outgoing, incoming, null) { sample }
        tracks.incomingClip = clip
        tracks.reportIncoming(true)
        val on = AutoMixArtworkPreferences(miniPlayerTransition = false).gate(automix = true)
        val miniOnly = AutoMixArtworkPreferences(artTransition = false).gate(automix = true)
        val spotify = clip.copy(source = CanvasSource.SPOTIFY)
        assertFalse(tracks.canSampleIncomingAmbient(spotify))
        assertFalse(tracks.claimIncomingAmbientCapture(clip, miniOnly))
        assertTrue(tracks.claimIncomingAmbientCapture(clip, on))
        assertFalse(tracks.claimIncomingAmbientCapture(clip, on), "even an unusable read consumes the standby attempt")
        tracks.retainIncomingAmbient(clip.copy(url = "replaced.mp4"), on, palette = palette)
        tracks.retainIncomingAmbient(clip, miniOnly, palette = palette)
        assertTrue(tracks.sealIncoming())
        assertNull(tracks.incomingAmbient)

        sample = visual.copy(generation = visual.generation + 1L)
        tracks.retainIncomingAmbient(clip, on, palette = palette)
        assertNull(tracks.incomingAmbient)
        sample = visual.copy(phase = AutoMixVisualPhase.CANCELLED)
        tracks.retainIncomingAmbient(clip, on, palette = palette)
        assertNull(tracks.incomingAmbient)
        sample = visual.copy(
            phase = AutoMixVisualPhase.MIXING, swapSeconds = 0.5, artworkWindow = window,
            audioSeconds = 2.8, artworkProgress = 0.9f,
        )
        tracks.retainIncomingAmbient(clip, on, palette = palette)
        assertNull(tracks.incomingAmbient, "the late frame must be handled by ordinary retinting")
    }

    @Test
    fun frameArrivingDuringRevealFadesInFromTheDrawnBlend() {
        val palette = MeshPalette(listOf(Color.Red))
        var sample = visual
        val tracks = AutoMixCanvasTracks(visual.generation, outgoing, incoming, null) { sample }
        tracks.incomingClip = clip
        tracks.reportIncoming(true)
        assertTrue(tracks.sealIncoming())
        sample = visual.copy(
            phase = AutoMixVisualPhase.MIXING,
            swapSeconds = 0.5,
            artworkWindow = AutoMixArtworkWindow(1.0, 3.0),
            audioSeconds = 1.7,
            artworkProgress = 0.35f,
        )
        tracks.retainIncomingAmbient(clip, AutoMixArtworkGate(), palette = palette)
        val accepted = requireNotNull(tracks.incomingAmbient)
        assertEquals(0f, accepted.alphaAt(AutoMixArtworkTuning.revealProgress(sample.artworkProgress)))
        assertTrue(accepted.alphaAt(0.75f) in 0.01f..0.99f)
        assertEquals(1f, accepted.alphaAt(1f))
        tracks.retainIncomingAmbient(clip, AutoMixArtworkGate(), palette = MeshPalette(listOf(Color.Blue)))
        assertSame(accepted, tracks.incomingAmbient)
    }

    @Test
    fun losingTheSurfaceFadesTheVisibleCanvasColorsBackToTheStill() {
        val palette = MeshPalette(listOf(Color.Red))
        var sample = visual
        val tracks = AutoMixCanvasTracks(visual.generation, outgoing, incoming, null) { sample }
        tracks.incomingClip = clip
        tracks.reportIncoming(true)
        tracks.retainIncomingAmbient(clip, AutoMixArtworkGate(), palette = palette)
        assertTrue(tracks.sealIncoming())
        val before = requireNotNull(tracks.incomingAmbient)
        sample = visual.copy(
            phase = AutoMixVisualPhase.MIXING, swapSeconds = 0.5,
            artworkWindow = AutoMixArtworkWindow(1.0, 3.0), audioSeconds = 2.0, artworkProgress = 0.5f,
        )
        val loss = AutoMixArtworkTuning.revealProgress(sample.artworkProgress)
        tracks.invalidateIncomingAmbient(clip)
        assertTrue(tracks.liveIncoming, "the native ambient fence does not change V5's artwork readiness")
        val retiring = requireNotNull(tracks.incomingAmbient)
        assertEquals(before.alphaAt(loss), retiring.alphaAt(loss))
        assertEquals(before.stillAlphaAt(loss), retiring.stillAlphaAt(loss))
        assertEquals(0f, retiring.alphaAt(1f))
        assertEquals(1f, retiring.stillAlphaAt(1f))
        assertNull(AutoMixAmbient(settled = tracks.generation to tracks.incoming).settledCanvas(retiring))
    }

    @Test
    fun anInFlightFrameFromAnInvalidatedSurfaceCannotPublishEvenIfReadinessRecovers() {
        val tracks = AutoMixCanvasTracks(visual.generation, outgoing, incoming, null) { visual }
        val gate = AutoMixArtworkGate()
        tracks.incomingClip = clip
        tracks.reportIncoming(true)
        assertTrue(tracks.claimIncomingAmbientCapture(clip, gate))
        tracks.invalidateIncomingAmbient(clip.copy(url = "other.mp4"))
        assertTrue(tracks.wantsIncomingAmbient(clip, gate))
        tracks.invalidateIncomingAmbient(clip)
        tracks.retainIncomingAmbient(clip, gate, palette = MeshPalette(listOf(Color.Red)))
        assertTrue(tracks.sealIncoming())
        assertNull(tracks.incomingAmbient)
    }

    @Test
    fun theNativeStandbyCanInvalidateItsSeedUntilItRecomposesAsCurrent() {
        val owner = AutoMixCanvasPair()
        var sample = visual
        val tracks = AutoMixCanvasTracks(visual.generation, outgoing, incoming, null) { sample }
        val gate = AutoMixArtworkGate()
        tracks.incomingClip = clip
        tracks.reportIncoming(true)
        tracks.retainIncomingAmbient(clip, gate, palette = MeshPalette(listOf(Color.Red)))
        assertTrue(tracks.sealIncoming())
        val standby = requireNotNull(owner.standbyFrameCapture(tracks, clip, gate))
        sample = visual.copy(phase = AutoMixVisualPhase.COMPLETED, artworkProgress = 1f)
        owner.finish(tracks)
        assertSame(tracks, owner.promoted)
        requireNotNull(tracks.incomingAmbient)

        standby.invalidate()
        assertTrue(tracks.liveIncoming)
        assertSame(clip, owner.promoted?.incomingClip)
        assertNull(owner.promoted?.incomingAmbient)
    }

    @Test
    fun frameAfterDeadlineCannotRejoinTheDissolve() {
        val tracks = AutoMixCanvasTracks(1L, outgoing, incoming, null) { visual }
        tracks.incomingClip = clip
        assertFalse(tracks.sealIncoming())
        tracks.reportIncoming(true)
        assertFalse(tracks.sealIncoming())
        assertFalse(tracks.liveIncoming)
    }

    @Test
    fun frameAfterAudioDeadlineCannotWinBeforeTheCollectorSealsTheChoice() {
        val window = AutoMixArtworkWindow(1.0, 3.0)
        val missed = visual.copy(
            phase = AutoMixVisualPhase.MIXING,
            swapSeconds = 0.5,
            artworkWindow = window,
            audioSeconds = window.startSeconds - AutoMixArtworkTuning.CANVAS_READY_MARGIN_SECONDS,
        )
        val tracks = AutoMixCanvasTracks(visual.generation, outgoing, incoming, null) { missed }
        tracks.incomingClip = clip
        tracks.reportIncoming(true)
        assertFalse(tracks.sealIncoming())
        assertFalse(tracks.liveIncoming)

        var sample = missed.copy(generation = visual.generation + 1L, audioSeconds = 0.5)
        val onTime = AutoMixCanvasTracks(visual.generation, outgoing, incoming, null) { sample }
        onTime.incomingClip = clip
        onTime.reportIncoming(true)
        assertFalse(onTime.incomingReady)
        sample = missed.copy(audioSeconds = missed.audioSeconds - 0.001)
        onTime.reportIncoming(true)
        assertTrue(onTime.sealIncoming())
        assertTrue(onTime.liveIncoming)
    }

    @Test
    fun acceptedClipThatLosesItsSurfaceFallsBackForTheRestOfTheMix() {
        val tracks = AutoMixCanvasTracks(1L, outgoing, incoming, null) { visual }
        tracks.incomingClip = clip
        tracks.reportIncoming(true)
        assertTrue(tracks.sealIncoming())
        tracks.reportIncoming(false)
        tracks.reportIncoming(true)
        assertNull(tracks.incomingClip)
        assertFalse(tracks.liveIncoming)
    }

    @Test
    fun missingSelectionDoesNotConsumeTheGeneration() {
        val owner = AutoMixCanvasPair()
        assertNull(owner.start(visual, null))
        assertNull(owner.start(visual, incoming))
        val started = requireNotNull(owner.start(visual, outgoing))
        assertSame(started, owner.visual)
        assertNull(owner.start(visual, outgoing))
    }

    @Test
    fun leavingPromotedOccurrenceAndReturningDoesNotRestoreOldOwnership() {
        val owner = AutoMixCanvasPair()
        val tracks = requireNotNull(owner.start(visual, outgoing))
        tracks.incomingClip = clip
        tracks.incomingReady = true
        assertTrue(tracks.sealIncoming())
        owner.finish(tracks)
        owner.displayed(outgoing, null, false)
        owner.displayed(incoming, null, false)
        assertNull(owner.promoted)
        assertNull(owner.start(visual, outgoing))
    }
}
