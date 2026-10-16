/**
 * Crossfade wrapper for the landscape player's still artwork.
 *
 * Wraps only the still image (shadow box, placeholder, AsyncImage) in
 * AnimatedContent keyed on song.videoId so that on track change the old cover
 * fades out while the new one fades in — both composed simultaneously.
 *
 * The canvas/video overlay is NOT inside the crossfade: it stays outside and
 * is not recreated on every track change.
 */
package com.music.bitchord.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import coil3.request.ImageRequest

/**
 * Default artwork crossfade duration matching ConvX's ambientTransitionMillis(0) = 1100ms.
 */
private const val DEFAULT_ARTWORK_FADE_MS = 1100

/**
 * Easing matching ConvX's SeamlessEasing: CubicBezierEasing(0.37f, 0f, 0.63f, 1f).
 */
private val CrossfadeEasing = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)

/**
 * Wraps the landscape still image in a crossfade that fires when the track changes.
 *
 * The canvas/video overlay is rendered OUTSIDE this composable and must be
 * placed after the call site so it is not recreated on every track change.
 *
 * @param videoId Stable track identity — keyed so URL upgrades within one song
 *   do not trigger a transition.
 * @param artRequest The current artwork image request (from PlayerArtwork).
 * @param sleeveShape Shape of the landscape sleeve corners (lambda).
 * @param shadowFraction 0..1 controlling shadow intensity (lambda).
 * @param modifier Modifier to apply to the entire crossfade container.
 */
@Composable
internal fun LandscapeArtworkCrossfade(
    videoId: String,
    artRequest: ImageRequest?,
    sleeveShape: () -> Shape,
    shadowFraction: () -> Float,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = TrackArtRequest(videoId, artRequest),
        contentKey = { it.videoId },
        transitionSpec = {
            fadeIn(tween(DEFAULT_ARTWORK_FADE_MS, easing = CrossfadeEasing))
                .togetherWith(fadeOut(tween(DEFAULT_ARTWORK_FADE_MS, easing = CrossfadeEasing)))
        },
        modifier = modifier,
        contentAlignment = Alignment.Center,
        label = "landscapeArtworkCrossfade",
    ) { artTarget ->
        LandscapeStillImage(
            artRequest = artTarget.request,
            sleeveShape = sleeveShape,
            shadowFraction = shadowFraction,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
