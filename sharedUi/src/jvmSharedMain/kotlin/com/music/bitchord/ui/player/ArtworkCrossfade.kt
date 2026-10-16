/**
 * Crossfade wrapper for artwork layers on the main player screen.
 *
 * Wraps an outgoing and incoming image in AnimatedContent so that on track
 * change the old cover fades out while the new one fades in — both composed
 * simultaneously. Uses song.videoId as the content key so quality upgrades
 * within one song do not trigger a transition.
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest

/**
 * Key for the artwork crossfade: carries both a stable id (for contentKey)
 * and the actual ImageRequest (which changes on track change, driving the fade).
 */
internal data class TrackArtRequest(val videoId: String, val request: ImageRequest?)

/**
 * Default easing matching ConvX's SeamlessEasing: CubicBezierEasing(0.37f, 0f, 0.63f, 1f).
 */
private val CrossfadeEasing = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)

/**
 * Default fade duration matching ConvX's ambientTransitionMillis(0) = 1100ms.
 */
const val DEFAULT_ARTWORK_FADE_MS = 1100

/**
 * Wraps an artwork image in a crossfade that fires when [target] changes.
 *
 * The outgoing layer stays composed and fades out while the incoming one fades
 * in — both visible simultaneously during the transition. The animation is
 * keyed on [TrackArtRequest.videoId] so quality upgrades within one song (which
 * change the URL but not the videoId) do not trigger a crossfade.
 *
 * Pass `art.request` directly as the target so the outgoing layer uses its own
 * request instance, avoiding pitfall 2 (both layers showing the new artwork).
 *
 * @param target The track identity + image request that drives the crossfade.
 * @param durationMs Fade duration in milliseconds (default 1100).
 * @param onState Callback for coil3's image loading states — typically art::onState.
 *   Only fires for the incoming layer; the outgoing layer is just fading out.
 * @param canvasRendered Whether a canvas overlay has rendered at least one frame.
 *   Used for TextureView stacking fix: skip drawing the outgoing image while a
 *   TextureView-backed canvas frame may still be compositing above it.
 * @param placeholder Optional composable drawn while both images are loading.
 * @param canvasContent Optional composable for a canvas overlay inside the
 *   crossfade — when provided, the outgoing canvas fades alongside the still.
 */
@Composable
internal fun ArtworkCrossfade(
    target: TrackArtRequest,
    durationMs: Int = DEFAULT_ARTWORK_FADE_MS,
    onState: (AsyncImagePainter.State) -> Unit = {},
    canvasRendered: Boolean = false,
    placeholder: (@Composable () -> Unit)? = null,
    canvasContent: (@Composable () -> Unit)? = null,
) {
    AnimatedContent(
        targetState = target,
        // Key on videoId so URL upgrades within one song don't trigger a transition.
        contentKey = { it.videoId },
        transitionSpec = {
            fadeIn(tween(durationMs, easing = CrossfadeEasing))
                .togetherWith(fadeOut(tween(durationMs, easing = CrossfadeEasing)))
        },
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
        label = "artworkCrossfade",
    ) { artTarget ->
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            // Placeholder (e.g. the MusicNote icon) shown while nothing is loaded.
            placeholder?.let { it() }

            val request = artTarget.request
            if (request != null) {
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onState = onState,
                    // TextureView-backed canvas frames can arrive before Coil has
                    // decoded the sleeve. Skip the draw call outright so nothing
                    // composites in the wrong order. The request stays mounted so
                    // loading still finishes in the background.
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent { if (artTarget.request != null || !canvasRendered) drawContent() },
                )
            }
            // Canvas overlay inside crossfade: fades alongside the still image.
            canvasContent?.invoke()
        }
    }
}
