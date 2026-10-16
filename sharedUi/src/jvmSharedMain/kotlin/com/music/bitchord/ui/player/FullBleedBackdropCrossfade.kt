/**
 * Crossfade wrapper for the full-bleed artwork backdrop.
 *
 * Wraps [FullArtworkBlurBackdrop] in AnimatedContent keyed on song.videoId so
 * that on track change the old blurred bitmap fades out while the new one fades
 * in — both composed simultaneously during the transition.
 *
 * The existing panel-driven alpha animation (fullArtworkBackdropAlpha) is NOT
 * affected: it controls visibility when lyrics/queue panels open, independent
 * of track-change crossfading.
 */
package com.music.bitchord.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import com.music.bitchord.ui.player.ArtworkCrossfade.DEFAULT_ARTWORK_FADE_MS

/**
 * Easing matching ConvX's SeamlessEasing: CubicBezierEasing(0.37f, 0f, 0.63f, 1f).
 */
private val CrossfadeEasing = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)

/**
 * Wraps the full-bleed backdrop in a crossfade that fires when the track changes.
 *
 * @param videoId Stable track identity — keyed so URL upgrades within one song
 *   do not trigger a transition.
 * @param blurredBitmap The prepared blurred bitmap for the current track (may be null).
 * @param modifier Modifier to apply to the entire crossfade container.
 */
@Composable
internal fun FullBleedBackdropCrossfade(
    videoId: String,
    blurredBitmap: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = TrackArtBitmap(videoId, blurredBitmap),
        contentKey = { it.videoId },
        transitionSpec = {
            fadeIn(tween(DEFAULT_ARTWORK_FADE_MS, easing = CrossfadeEasing))
                .togetherWith(fadeOut(tween(DEFAULT_ARTWORK_FADE_MS, easing = CrossfadeEasing)))
        },
        modifier = modifier,
        contentAlignment = Alignment.Center,
        label = "fullBleedBackdropCrossfade",
    ) { artTarget ->
        FullArtworkBlurBackdrop(
            image = artTarget.bitmap,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Key for the full-bleed backdrop crossfade.
 */
internal data class TrackArtBitmap(val videoId: String, val bitmap: ImageBitmap?)
