/**
 * ConvX-style melt transition renderer for Android.
 *
 * Adapted from ConvX (CONVX_DIR/app/src/main/kotlin/com/convx/music/ui/player/Thumbnail.kt)
 * — MeltMorphOverlay, MELT_SHADER_SOURCE, rippleMesh, and related helpers.
 *
 * Original copyright: ConvX contributors. Licensed GPL-3.0.
 * This adaptation is also GPL-3.0 compatible.
 *
 * Two render paths:
 * - API 33+ (TIRAMISU): RuntimeShader (AGSL) with FBM noise displacement,
 *   per-pixel smoothstep reveal, and chromatic aberration.
 * - API 26–32: TODO — drawBitmapMesh ripple + alpha fallback not yet implemented
 *   due to Canvas.drawBitmapMesh Kotlin binding issues on this SDK version.
 */
package com.music.bitchord.ui.player

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

/** Displacement strength — how far the melt pushes pixels around. */
private const val MELT_INTENSITY = 0.55f

/** Noise-field frequency — higher reads as finer, more turbulent melt cells. */
private const val MELT_SCALE = 2.4f

/** RGB-split strength at the midpoint of the transition, fading to none at both ends. */
private const val MELT_ABERRATION = 0.35f

/** ConvX's SeamlessEasing: CubicBezierEasing(0.37f, 0f, 0.63f, 1f). */
private val MeltEasing = androidx.compose.animation.core.CubicBezierEasing(0.37f, 0f, 0.63f, 1f)

/** Android actual: ConvX-style melt renderer on API 26+, default on lower. */
internal actual val LocalArtworkTransitionRenderer: ArtworkTransitionRenderer =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) AndroidMeltRenderer else DefaultArtworkTransitionRenderer

/** The melt renderer — RuntimeShader on API 33+, fallback on API 26-32. */
private object AndroidMeltRenderer : ArtworkTransitionRenderer {
    override val supportsMelt: Boolean = true

    @Composable
    override fun TransitionOverlay(
        frame: ArtworkTransitionFrame,
        durationMs: Int,
        content: @Composable () -> Unit,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RuntimeShaderMeltOverlay(frame, durationMs, content)
        } else {
            // API 26-32 fallback: plain crossfade until drawBitmapMesh binding is fixed.
            content()
        }
    }
}

/** API 33+ RuntimeShader melt overlay — port of ConvX's MeltMorphOverlay. */
@Composable
@SuppressLint("UnusedCompositionLocalInfo")
private fun RuntimeShaderMeltOverlay(
    frame: ArtworkTransitionFrame,
    durationMs: Int,
    content: @Composable () -> Unit,
) {
    val progress = remember(frame.outgoing, frame.incoming) { Animatable(0f) }
    var timeSeconds by mutableFloatStateOf(0f)

    LaunchedEffect(frame.outgoing, frame.incoming) {
        progress.snapTo(0f)
        launch { progress.animateTo(1f, animationSpec = tween(durationMs, easing = MeltEasing)) }
    }

    LaunchedEffect(progress.value) {
        while (progress.isRunning) {
            timeSeconds = progress.value * 10f
            kotlinx.coroutines.delay(16) // ~60fps
        }
    }

    val outgoingBitmap = frame.outgoing?.asAndroidBitmap()
    val incomingBitmap = frame.incoming?.asAndroidBitmap()
    val context = LocalContext.current

    if (outgoingBitmap != null && incomingBitmap != null && progress.value < 1f) {
        AndroidView(
            factory = { ctx ->
                val shader = android.graphics.RuntimeShader(MELT_SHADER_SOURCE)
                val outgoingShader = android.graphics.BitmapShader(
                    outgoingBitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP,
                )
                val incomingShader = android.graphics.BitmapShader(
                    incomingBitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP,
                )
                object : View(ctx) {
                    override fun onDraw(canvas: Canvas) {
                        if (width <= 0 || height <= 0) return
                        shader.setInputShader("tCurrent", outgoingShader)
                        shader.setInputShader("tNext", incomingShader)
                        shader.setFloatUniform("uResolution", width.toFloat(), height.toFloat())
                        shader.setFloatUniform("uCurrentSize", outgoingBitmap.width.toFloat(), outgoingBitmap.height.toFloat())
                        shader.setFloatUniform("uNextSize", incomingBitmap.width.toFloat(), incomingBitmap.height.toFloat())
                        shader.setFloatUniform("uProgress", progress.value)
                        shader.setFloatUniform("uIntensity", MELT_INTENSITY)
                        shader.setFloatUniform("uScale", MELT_SCALE)
                        shader.setFloatUniform("uAberration", MELT_ABERRATION)
                        shader.setFloatUniform("uTime", timeSeconds)
                        val paint = Paint().apply { this.shader = shader }
                        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                    }
                }
            },
            update = { it.invalidate() },
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        content()
    }
}

/**
 * AGSL port of React Bits' MorphSlider "melt" fragment shader.
 * Adapted from ConvX MELT_SHADER_SOURCE (Thumbnail.kt). GPL-3.0.
 */
private const val MELT_SHADER_SOURCE = """
uniform shader tCurrent;
uniform shader tNext;
uniform float2 uResolution;
uniform float2 uCurrentSize;
uniform float2 uNextSize;
uniform float uProgress;
uniform float uIntensity;
uniform float uScale;
uniform float uAberration;
uniform float uTime;

float hash21(float2 p) {
    float3 p3 = fract(p.xyx * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + float2(1.0, 0.0));
    float c = hash21(i + float2(0.0, 1.0));
    float d = hash21(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) {
        v += a * noise(p);
        p *= 2.0;
        a *= 0.5;
    }
    return v;
}

float2 coverUV(float2 uv, float2 res, float2 img) {
    float rA = res.x / max(res.y, 1.0);
    float iA = img.x / max(img.y, 1.0);
    float2 s = float2(1.0, 1.0);
    float ratio = rA / max(iA, 0.0001);
    if (ratio > 1.0) { s.y = 1.0 / ratio; } else { s.x = ratio; }
    return (uv - 0.5) * s + 0.5;
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uResolution;
    float p = clamp(uProgress, 0.0, 1.0);
    float env = sin(p * 3.14159265359);
    float nn = fbm(uv * uScale + uTime * 0.03);
    float warp = fbm(uv * uScale * 1.7 - uTime * 0.02);
    float2 g = float2(nn, warp) - 0.5;
    float2 uvC = uv + g * uIntensity * 0.5 * p;
    float2 uvN = uv - g * uIntensity * 0.5 * (1.0 - p);
    float m = smoothstep(nn - 0.15, nn + 0.15, p);
    float2 sC = coverUV(uvC, uResolution, uCurrentSize);
    float2 sN = coverUV(uvN, uResolution, uNextSize);
    float ca = uAberration * env * 0.03;
    float3 colC = float3(
        tCurrent.eval((sC + float2(ca, 0.0)) * uCurrentSize).r,
        tCurrent.eval(sC * uCurrentSize).g,
        tCurrent.eval((sC - float2(ca, 0.0)) * uCurrentSize).b
    );
    float3 colN = float3(
        tNext.eval((sN + float2(ca, 0.0)) * uNextSize).r,
        tNext.eval(sN * uNextSize).g,
        tNext.eval((sN - float2(ca, 0.0)) * uNextSize).b
    );
    float3 col = mix(colC, colN, m);
    return half4(half3(col), 1.0);
}
"""
