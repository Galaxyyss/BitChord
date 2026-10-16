/**
 * Crossfade wrapper for the landscape player's still artwork.
 *
 * Wraps only the still image (shadow box, placeholder, AsyncImage) in
 * AnimatedContent keyed on song.videoId so that on track change the old cover
 * fades out while the new one fades in — both composed simultaneously.
 *
 * When [canvas] is provided, the canvas overlay is also composed inside the
 * AnimatedContent so it participates in the crossfade: the outgoing canvas
 * fades its alpha to 0 while the incoming canvas fades in.
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Shape
import coil3.request.ImageRequest
import com.music.bitchord.data.canvas.CanvasArtwork

/**
 * Easing matching ConvX's SeamlessEasing: CubicBezierEasing(0.37f, 0f, 0.63f, 1f).
 */
private val CrossfadeEasing = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)

/**
 * Wraps the landscape still image in a crossfade that fires when the track changes.
 *
 * When [canvas] is non-null, it is composed inside the AnimatedContent so the
 * outgoing canvas fades out alongside the still image.
 *
 * @param videoId Stable track identity — keyed so URL upgrades within one song
 *   do not trigger a transition.
 * @param artRequest The current artwork image request (from PlayerArtwork).
 * @param durationMs Fade duration in milliseconds (default 1100).
 * @param sleeveShape Shape of the landscape sleeve corners (lambda).
 * @param shadowFraction 0..1 controlling shadow intensity (lambda).
 * @param canvas The current canvas artwork for this track (nullable).
 * @param modifier Modifier to apply to the entire crossfade container.
 */
@Composable
internal fun LandscapeArtworkCrossfade(
    videoId: String,
    artRequest: ImageRequest?,
    durationMs: Int = DEFAULT_ARTWORK_FADE_MS,
    sleeveShape: () -> Shape,
    shadowFraction: () -> Float,
    canvas: CanvasArtwork?,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = TrackArtRequest(videoId, artRequest),
        contentKey = { it.videoId },
        transitionSpec = {
            fadeIn(tween(durationMs, easing = CrossfadeEasing))
                .togetherWith(fadeOut(tween(durationMs, easing = CrossfadeEasing)))
        },
        modifier = modifier,
        contentAlignment = Alignment.Center,
        label = "landscapeArtworkCrossfade",
    ) { artTarget ->
        Box(modifier = Modifier.fillMaxSize()) {
            LandscapeStillImage(
                artRequest = artTarget.request,
                sleeveShape = sleeveShape,
                shadowFraction = shadowFraction,
                modifier = Modifier.fillMaxSize(),
            )

            // Canvas overlay inside crossfade: fades alongside the still image.
            canvas?.let { clip ->
                CanvasArtworkPlayer(
                    canvas = clip,
                    isPlaying = true,
                    pausedForTransition = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
