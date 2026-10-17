/**
 * CompositionLocal providing the artwork transition renderer.
 *
 * Default (desktop/JVM) = plain AnimatedContent crossfade.
 * Android actual = ConvX-style melt overlay with RuntimeShader (API 33+) or
 * drawBitmapMesh ripple fallback (API 26-32).
 */
package com.music.bitchord.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap

/**
 * A single frame of artwork transition: holds both outgoing and incoming images
 * plus the current blend progress (0→1).
 */
internal data class ArtworkTransitionFrame(
    val outgoing: ImageBitmap?,
    val incoming: ImageBitmap?,
    val progress: Float,
)

/**
 * Render a single frame of artwork transition. Called on every animation tick
 * during the crossfade. The renderer may choose to draw nothing (plain fade),
 * or compose a melt overlay on top of the standard AnimatedContent layers.
 */
internal interface ArtworkTransitionRenderer {
    @Composable
    fun TransitionOverlay(
        frame: ArtworkTransitionFrame,
        durationMs: Int,
        content: @Composable () -> Unit,
    )

    val supportsMelt: Boolean
}

/** Default implementation: no overlay, just passes through content unchanged. */
internal object DefaultArtworkTransitionRenderer : ArtworkTransitionRenderer {
    @Composable
    override fun TransitionOverlay(
        frame: ArtworkTransitionFrame,
        durationMs: Int,
        content: @Composable () -> Unit,
    ) {
        content()
    }

    override val supportsMelt: Boolean = false
}

/** Desktop/JVM default — plain crossfade. */
internal val LocalArtworkTransitionRendererDefault: ProvidableCompositionLocal<ArtworkTransitionRenderer> =
    staticCompositionLocalOf { DefaultArtworkTransitionRenderer }

/** Actual provided per-platform: desktop = default, Android = melt renderer. */
internal expect val LocalArtworkTransitionRenderer: ArtworkTransitionRenderer
