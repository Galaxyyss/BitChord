package com.music.bitchord.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.unit.Dp
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.ic_player_next
import com.music.bitchord.sharedui.resources.ic_player_previous
import com.music.bitchord.ui.rememberIsForeground
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.vectorResource
import kotlin.math.pow

/**
 * Fits to the supplied ~30fps Next recording, not published Apple constants.
 * Progress is elapsed time / 200ms on an isolated tap; only a retarget changes
 * its speed. All geometry comes from the existing drawable, never a new triangle.
 */
private object SkipMotion {
    const val DurationMs = 200
    const val VisibilityThreshold = 0.0001f
    const val FrontGone = 0.20f
    const val FrontShrinkPower = 6
    const val SurvivorCentered = 0.30f
    const val Settled = 0.85f
    const val NewOnset = 0.28f
    const val NewFirstFrame = 0.33f
    const val NewFirstScale = 0.35f
    const val NewMiddleFrame = 0.50f
    const val NewMiddleScale = 0.60f
    const val NewLateFrame = 0.67f
    const val NewLateScale = 0.88f
    const val NewInitialAlpha = 0.40f
    const val NewFirstAlpha = 0.45f
    // Scale / cycle-progress tangents: smooth monotone fits to the coarse samples.
    const val NewOnsetRate = 10f
    const val NewFirstRate = 2.4f
    const val NewMiddleRate = 1.55f
    const val NewLateRate = 1.1f
    const val CircleDiameter = 1.7f
    const val CircleAlpha = 0.08f
    const val CircleFeatherStart = 0.80f
    const val CircleInMs = 45
    const val CircleOutMs = 100
    // A cubic Hermite easing with start slope <= 2 and end slope 1 is monotone.
    const val MaxStartSlope = 2f
    const val MillisPerSecond = 1000f
}

/** Visual intent only. The owner still calls its original playback action on click. */
class PlayerSkipMotion internal constructor(
    internal val active: State<Boolean>,
    internal val reduceMotion: State<Boolean>,
) {
    internal val cycles = Animatable(0f, visibilityThreshold = SkipMotion.VisibilityThreshold)
    internal var requestedCycles by mutableIntStateOf(0)
    // Animatable clears velocity on cancellation. Save it before effect cancellation
    // so the next tween's easing can continue the actual incoming speed.
    internal var carriedVelocity = 0f

    fun onTap() {
        requestedCycles++
    }
}

@Composable
fun rememberPlayerSkipMotion(active: Boolean, reduceMotion: Boolean): PlayerSkipMotion {
    val foreground = rememberIsForeground()
    val activeState = rememberUpdatedState(active && foreground)
    val reduceMotionState = rememberUpdatedState(reduceMotion)
    val motion = remember { PlayerSkipMotion(activeState, reduceMotionState) }
    LaunchedEffect(motion.requestedCycles, active, foreground, reduceMotion) {
        val target = motion.requestedCycles.toFloat()
        val distance = target - motion.cycles.value
        if (!active || !foreground || reduceMotion) {
            motion.cycles.snapTo(target)
            motion.carriedVelocity = 0f
        } else if (distance > 0f) {
            val velocity = if (motion.carriedVelocity > 0f) motion.carriedVelocity else {
                distance * SkipMotion.MillisPerSecond / SkipMotion.DurationMs
            }
            // Finish within one tap window, even when several taps coalesce into
            // one frame. Shorten it if needed to retain velocity without overshoot.
            val duration = minOf(
                SkipMotion.DurationMs,
                (SkipMotion.MaxStartSlope * distance * SkipMotion.MillisPerSecond / velocity)
                    .toInt(),
            ).coerceAtLeast(1)
            val slope = (velocity * duration / (distance * SkipMotion.MillisPerSecond))
                .coerceIn(0f, SkipMotion.MaxStartSlope)
            motion.cycles.animateTo(
                targetValue = target,
                animationSpec = tween(
                    durationMillis = duration,
                    easing = Easing { t ->
                        // Hermite(0, 1, slope, 1). At slope=1 this is exactly linear.
                        (slope - 1f) * t * t * t + 2f * (1f - slope) * t * t + slope * t
                    },
                ),
                initialVelocity = velocity,
            ) {
                motion.carriedVelocity = this.velocity
            }
            motion.carriedVelocity = 0f
        }
    }
    return motion
}

internal data class SkipPose(
    val frontScale: Float,
    val survivorTravel: Float,
    val newScale: Float,
    val newAlpha: Float,
)

/** Pure, non-overshooting conveyor pose; endpoint contours are the original slots. */
internal fun playerSkipPose(progress: Float): SkipPose {
    val p = progress.coerceIn(0f, 1f)
    val front = (1f - (p / SkipMotion.FrontGone).coerceIn(0f, 1f))
        .pow(SkipMotion.FrontShrinkPower)
    val travel = if (p < SkipMotion.SurvivorCentered) {
        0.5f * skipEaseOut(p / SkipMotion.SurvivorCentered)
    } else {
        0.5f + 0.5f * skipEaseOut(
            (p - SkipMotion.SurvivorCentered) / (SkipMotion.Settled - SkipMotion.SurvivorCentered),
        )
    }
    // Monotone, tangent-continuous fits through the coarse video samples.
    // They decelerate into the resting pose, with no spring or scale overshoot.
    val growth = when {
        p <= SkipMotion.NewOnset -> 0f
        p < SkipMotion.NewFirstFrame -> skipGrowth(p,
            SkipMotion.NewOnset, SkipMotion.NewFirstFrame, 0f, SkipMotion.NewFirstScale,
            SkipMotion.NewOnsetRate, SkipMotion.NewFirstRate)
        p < SkipMotion.NewMiddleFrame -> skipGrowth(p,
            SkipMotion.NewFirstFrame, SkipMotion.NewMiddleFrame, SkipMotion.NewFirstScale, SkipMotion.NewMiddleScale,
            SkipMotion.NewFirstRate, SkipMotion.NewMiddleRate)
        p < SkipMotion.NewLateFrame -> skipGrowth(p,
            SkipMotion.NewMiddleFrame, SkipMotion.NewLateFrame, SkipMotion.NewMiddleScale, SkipMotion.NewLateScale,
            SkipMotion.NewMiddleRate, SkipMotion.NewLateRate)
        p < SkipMotion.Settled -> skipGrowth(p,
            SkipMotion.NewLateFrame, SkipMotion.Settled, SkipMotion.NewLateScale, 1f,
            SkipMotion.NewLateRate, 0f)
        else -> 1f
    }
    val newAlpha = if (growth <= SkipMotion.NewFirstScale) {
        skipMix(SkipMotion.NewInitialAlpha, SkipMotion.NewFirstAlpha, growth / SkipMotion.NewFirstScale)
    } else {
        skipMix(SkipMotion.NewFirstAlpha, 1f,
            (growth - SkipMotion.NewFirstScale) / (1f - SkipMotion.NewFirstScale))
    }
    return SkipPose(front, travel, growth, newAlpha)
}

private fun skipGrowth(
    progress: Float,
    start: Float,
    end: Float,
    startScale: Float,
    endScale: Float,
    startRate: Float,
    endRate: Float,
): Float {
    val span = end - start
    val t = (progress - start) / span
    val t2 = t * t
    val t3 = t2 * t
    return (2f * t3 - 3f * t2 + 1f) * startScale +
        (t3 - 2f * t2 + t) * span * startRate +
        (-2f * t3 + 3f * t2) * endScale + (t3 - t2) * span * endRate
}

private fun skipMix(start: Float, end: Float, progress: Float): Float =
    start + (end - start) * progress

private fun skipEaseOut(progress: Float): Float {
    val remaining = 1f - progress.coerceIn(0f, 1f)
    return 1f - remaining * remaining
}

/**
 * Use the original painter at rest, including the final 15% of each cycle.
 * During motion, split its actual contours, not clipped halves of the painter:
 * the current rounded arrowheads' bounding boxes overlap slightly.
 */
@Composable
fun PlayerSkipGlyph(
    motion: PlayerSkipMotion,
    contentDescription: String,
    tint: Color,
    modifier: Modifier = Modifier,
    previous: Boolean = false,
) {
    val resource = if (previous) Res.drawable.ic_player_previous else Res.drawable.ic_player_next
    val vector = vectorResource(resource)
    // Compose resources can return an empty vector while their asynchronous
    // resource state loads. Keep the original painter, rather than index root[0].
    if (vector.root.size == 0) {
        Icon(
            painter = painterResource(resource),
            contentDescription = contentDescription,
            tint = tint,
            modifier = modifier,
        )
        return
    }
    val nodes = (vector.root[0] as VectorPath).pathData
    val split = remember(nodes) { nodes.indexOfLast { it is PathNode.MoveTo } }
    val backNodes = remember(nodes) { nodes.subList(0, split) }
    val frontNodes = remember(nodes) { nodes.subList(split, nodes.size) }
    Icon(
        painter = painterResource(resource),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.drawWithCache {
            val back = backNodes.toPath()
            val front = frontNodes.toPath()
            val survivor = Path()
            val backCenter = back.getBounds().center
            val frontCenter = front.getBounds().center
            onDrawWithContent {
                val p = motion.cycles.value % 1f
                if (!motion.active.value || motion.reduceMotion.value || p == 0f || p >= SkipMotion.Settled) {
                    drawContent()
                } else {
                    val pose = playerSkipPose(p)
                    // Both vectors use absolute M/L/A/Z nodes with matching arc
                    // flags. Interpolating the source coordinates also preserves
                    // their sub-pixel differences instead of assuming translation
                    // alone can make the two existing arrowheads identical.
                    backNodes.mapIndexed { index, node ->
                        interpolateSkipNode(node, frontNodes[index], pose.survivorTravel)
                    }.toPath(survivor)
                    scale(size.width / vector.viewportWidth, size.height / vector.viewportHeight,
                        pivot = androidx.compose.ui.geometry.Offset.Zero) {
                        scale(pose.frontScale, pivot = frontCenter) {
                            drawPath(front, tint, alpha = pose.frontScale)
                        }
                        drawPath(survivor, tint)
                        scale(pose.newScale, pivot = backCenter) {
                            drawPath(back, tint, alpha = pose.newAlpha)
                        }
                    }
                }
            }
        },
    )
}

private fun interpolateSkipNode(from: PathNode, to: PathNode, progress: Float): PathNode =
    when (from) {
        is PathNode.MoveTo -> {
            to as PathNode.MoveTo
            PathNode.MoveTo(skipMix(from.x, to.x, progress), skipMix(from.y, to.y, progress))
        }
        is PathNode.LineTo -> {
            to as PathNode.LineTo
            PathNode.LineTo(skipMix(from.x, to.x, progress), skipMix(from.y, to.y, progress))
        }
        is PathNode.ArcTo -> {
            to as PathNode.ArcTo
            PathNode.ArcTo(
                horizontalEllipseRadius = skipMix(from.horizontalEllipseRadius, to.horizontalEllipseRadius, progress),
                verticalEllipseRadius = skipMix(from.verticalEllipseRadius, to.verticalEllipseRadius, progress),
                theta = skipMix(from.theta, to.theta, progress),
                isMoreThanHalf = from.isMoreThanHalf,
                isPositiveArc = from.isPositiveArc,
                arcStartX = skipMix(from.arcStartX, to.arcStartX, progress),
                arcStartY = skipMix(from.arcStartY, to.arcStartY, progress),
            )
        }
        else -> from // Close; verified against both current drawable paths.
    }

/** Expanded player only. No pointer handler, touch target, or semantics changes. */
@Composable
internal fun Modifier.playerSkipPressCircle(
    motion: PlayerSkipMotion,
    interactionSource: MutableInteractionSource,
    enabled: Boolean,
    tint: Color,
    glyphSize: Dp,
    diameter: Dp,
): Modifier {
    val target = motion.requestedCycles.toFloat()
    return playerPressCircle(
        interactionSource = interactionSource,
        trigger = motion.requestedCycles,
        enabled = enabled && motion.active.value,
        reduceMotion = motion.reduceMotion.value,
        tint = tint,
        glyphSize = glyphSize,
        diameter = diameter,
        hasMotion = { motion.cycles.value < target },
        settled = { motion.cycles.value >= target - (1f - SkipMotion.Settled) },
    )
}

/** Draw-only feedback shared by expanded transport controls, never mini buttons. */
@Composable
internal fun Modifier.playerPressCircle(
    interactionSource: MutableInteractionSource,
    trigger: Int,
    enabled: Boolean,
    reduceMotion: Boolean,
    tint: Color,
    glyphSize: Dp,
    diameter: Dp,
    hasMotion: () -> Boolean,
    settled: () -> Boolean,
    minimumHoldMs: Int = 0,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val foreground = rememberIsForeground()
    val opacity = remember { Animatable(0f) }
    val hold = remember { Animatable(0f) }
    var consumedTrigger by remember { mutableIntStateOf(trigger) }
    // Updated lambdas let playback change while the same press response awaits
    // the morph's first rise, without restarting the circle or its minimum hold.
    val currentSettled by rememberUpdatedState(settled)
    LaunchedEffect(pressed, trigger, foreground, enabled, reduceMotion) {
        val clicked = trigger != consumedTrigger
        consumedTrigger = trigger
        if (!foreground || !enabled || reduceMotion) {
            opacity.snapTo(0f)
        } else if (pressed || clicked || hasMotion()) {
            opacity.animateTo(1f, tween(SkipMotion.CircleInMs, easing = LinearOutSlowInEasing))
            if (!pressed) {
                if (minimumHoldMs > 0) {
                    // An animation-clock hold, not wall-clock delay: duration
                    // scale zero must collapse the whole response immediately.
                    hold.snapTo(0f)
                    hold.animateTo(1f, tween(minimumHoldMs))
                }
                snapshotFlow { currentSettled() }.first { it }
                opacity.animateTo(0f, tween(SkipMotion.CircleOutMs))
            }
        } else {
            // Cancelled presses never request a conveyor cycle or playback.
            opacity.animateTo(0f, tween(SkipMotion.CircleOutMs))
        }
    }
    return drawWithCache {
        // Existing skip touch boxes are smaller than the reference's 1.7x disc.
        // Cap rather than enlarge their targets or spill over neighbouring controls.
        val circleSize = minOf(diameter.toPx(), glyphSize.toPx() * SkipMotion.CircleDiameter)
        val radius = minOf(size.width, size.height, circleSize) / 2f
        val fill = tint.copy(alpha = tint.alpha * SkipMotion.CircleAlpha)
        val brush = Brush.radialGradient(
            0f to fill,
            SkipMotion.CircleFeatherStart to fill,
            1f to tint.copy(alpha = 0f),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = radius,
        )
        onDrawBehind {
            if (enabled && foreground && !reduceMotion) {
                drawCircle(brush, radius = radius, alpha = opacity.value)
            }
        }
    }
}
