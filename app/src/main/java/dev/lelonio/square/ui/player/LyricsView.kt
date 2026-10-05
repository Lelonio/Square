/*
 * Synced lyrics view, contributed by @donutnotnut in Discussion #50.
 *
 * Its animation is ported from Spicy Lyrics (LyricsAnimator.ts) by Spikerko,
 * https://github.com/Spikerko/spicy-lyrics, which is licensed under the GNU
 * Affero General Public License v3.0. This file is therefore distributed under
 * AGPL-3.0, combined with the rest of Square (GPL-3.0) as section 13 of both
 * licences allows.
 */
package dev.lelonio.square.ui.player

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.lelonio.square.data.LyricLine
import dev.lelonio.square.data.LyricWord
import dev.lelonio.square.data.Lyrics
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lyric visual and animation settings customizable by the user.
 */
@Immutable
data class LyricVisualSettings(
    val inactiveBlur: Float = 6.0f,
    val glowIntensity: Float = 1.0f,
    val glowRadius: Float = 1.0f,
    val jumpStrength: Float = 1.0f,
    val lyricsSize: Float = 1.0f,
)

val LocalLyricVisualSettings = compositionLocalOf { LyricVisualSettings() }

/**
 * Synced lyrics view ported directly from Spicy Lyrics (spicy-lyrics / LyricsAnimator.ts).
 */
@Composable
fun LyricsView(
    lyrics: Lyrics,
    positionMs: State<Long>,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    /** Whether to read each line in the listener's language underneath it. */
    showTranslation: Boolean = false,
    onSeek: (Long) -> Unit,
) {
    val listState = remember(lyrics) { LazyListState() }
    val position by rememberSmoothPosition(positionMs, isPlaying)

    val activeLine = remember(lyrics, position) {
        if (!lyrics.synced) -1
        else lyrics.lines.indexOfLast { (it.startTimeMs ?: 0L) <= position }
    }

    LaunchedEffect(activeLine) {
        if (activeLine < 0) return@LaunchedEffect
        val layoutInfo = listState.layoutInfo
        val viewportHeight = layoutInfo.viewportSize.height
        if (viewportHeight <= 0) return@LaunchedEffect

        val viewportCentre = viewportHeight / 2
        val visibleItem = layoutInfo.visibleItemsInfo.find { it.index == activeLine }

        if (visibleItem == null) {
            listState.animateScrollToItem(activeLine, -viewportCentre + LineHeightPx)
        } else {
            val itemCenter = visibleItem.offset + (visibleItem.size / 2)
            val delta = itemCenter - viewportCentre
            if (abs(delta) > 4) {
                listState.animateScrollBy(
                    value = delta.toFloat(),
                    animationSpec = tween(
                        durationMillis = 420,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
        }
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { clip = false }
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.18f to Color.Black,
                        0.82f to Color.Black,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
        contentPadding = PaddingValues(vertical = 40.dp),
    ) {
        itemsIndexed(lyrics.lines) { index, line ->
            val isActive = index == activeLine
            val distance = if (activeLine < 0) 0 else abs(index - activeLine)

            if (line.text.isBlank() || (line.text.trim() == "♪")) {
                InterludeDotsItem(
                    isActive = isActive,
                    onClick = { line.startTimeMs?.let { onSeek(it) } },
                )
            } else {
                LyricLineItem(
                    line = line,
                    translation = line.translation.takeIf { showTranslation },
                    positionMs = position,
                    distance = distance,
                    isActive = isActive,
                    unsynced = !lyrics.synced,
                    onClick = { line.startTimeMs?.let { onSeek(it) } },
                )
            }
        }
    }
}

/**
 * The playback position, advanced every frame between reports.
 */
@Composable
private fun rememberSmoothPosition(source: State<Long>, isPlaying: Boolean): State<Long> {
    val smoothed = remember { mutableLongStateOf(source.value) }
    val reported = source.value

    LaunchedEffect(reported, isPlaying) {
        smoothed.longValue = reported
        if (!isPlaying) return@LaunchedEffect

        val startedAt = withFrameMillis { it }
        while (true) {
            withFrameMillis { frame ->
                smoothed.longValue = reported + (frame - startedAt)
            }
        }
    }

    return smoothed
}

/**
 * Gives wrapped content extra space for glow/shadow bleed without affecting layout sizing.
 */
fun Modifier.overflowBounds(extra: Dp) = this.layout { measurable, constraints ->
    val extraPx = extra.roundToPx()
    val paddedConstraints = constraints.copy(
        maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + (extraPx * 2) else constraints.maxWidth,
        maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + (extraPx * 2) else constraints.maxHeight,
    )
    val placeable = measurable.measure(paddedConstraints)
    val width = (placeable.width - (extraPx * 2)).coerceAtLeast(0)
    val height = (placeable.height - (extraPx * 2)).coerceAtLeast(0)
    layout(width, height) {
        placeable.place(-extraPx, -extraPx)
    }
}

@Composable
private fun LyricLineItem(
    line: LyricLine,
    translation: String?,
    positionMs: Long,
    distance: Int,
    isActive: Boolean,
    unsynced: Boolean,
    onClick: () -> Unit,
) {
    val visualSettings = LocalLyricVisualSettings.current

    val targetAlpha = if (isActive) 1.0f else (0.62f - (distance * 0.12f)).coerceAtLeast(0.22f)
    val targetScale = if (isActive) 1.025f else 0.92f
    val targetBlur = if (isActive || unsynced) 0f else visualSettings.inactiveBlur

    val animatedAlpha by animateFloatAsState(
        targetValue = if (unsynced) 1f else targetAlpha,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "lyricAlpha",
    )

    val animatedScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "lyricScale",
    )

    val animatedBlur by animateFloatAsState(
        targetValue = targetBlur,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "lyricBlur",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !unsynced, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp)
            .overflowBounds(12.dp)
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
                alpha = animatedAlpha
                transformOrigin = TransformOrigin(0f, 0.5f)
                clip = false
                compositingStrategy = CompositingStrategy.ModulateAlpha

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    renderEffect = if (animatedBlur > 0.4f) {
                        RenderEffect
                            .createBlurEffect(animatedBlur, animatedBlur, Shader.TileMode.DECAL)
                            .asComposeRenderEffect()
                    } else {
                        null
                    }
                }
            }
            .zIndex(if (isActive) 100f else 1f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { clip = false },
        ) {
            if (isActive && line.words.isNotEmpty()) {
                SyllableSyncLine(
                    line = line,
                    progressProvider = { positionMs },
                )
            } else {
                val shadowBlur = 10f * visualSettings.glowIntensity * visualSettings.lyricsSize
                val shadowAlpha = (0.85f * visualSettings.glowIntensity).coerceIn(0f, 1f)
                val style = lyicTextStyle()

                Text(
                    text = line.text,
                    modifier = Modifier.fillMaxWidth(),
                    color = if (isActive) Color.White else Color.White.copy(alpha = 0.35f),
                    textAlign = TextAlign.Start,
                    softWrap = true,
                    style = if (isActive) {
                        style.copy(
                            shadow = if (shadowAlpha > 0f) {
                                Shadow(
                                    color = Color.White.copy(alpha = shadowAlpha),
                                    offset = Offset.Zero,
                                    blurRadius = shadowBlur,
                                )
                            } else null,
                        )
                    } else {
                        style
                    },
                )
            }

            if (!translation.isNullOrBlank()) {
                Text(
                    text = translation,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = (15 * visualSettings.lyricsSize).sp,
                        lineHeight = (19 * visualSettings.lyricsSize).sp,
                    ),
                    color = Color.White.copy(alpha = 0.62f),
                    textAlign = TextAlign.Start,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Character timing calculation helper for fine-grained per-letter synchronization.
 */
@Immutable
data class CharacterTiming(
    val char: Char,
    val startMs: Long,
    val endMs: Long,
    val isWhitespace: Boolean,
)

fun calculateCharacterTimings(word: LyricWord, piece: String): List<CharacterTiming> {
    val text = piece.trimEnd()
    if (text.isEmpty()) return emptyList()

    val durationMs = (word.endMs - word.startMs).coerceAtLeast(1L)
    val visibleCount = text.count { !it.isWhitespace() }

    if (visibleCount == 0) {
        return text.map { CharacterTiming(it, word.startMs, word.endMs, isWhitespace = true) }
    }

    var visibleIndex = 0
    return text.map { ch ->
        if (ch.isWhitespace()) {
            val start = if (visibleIndex == 0) word.startMs else word.startMs + (durationMs * visibleIndex) / visibleCount
            CharacterTiming(ch, start, word.endMs, isWhitespace = true)
        } else {
            val start = word.startMs + (durationMs * visibleIndex) / visibleCount
            val end = word.startMs + (durationMs * (visibleIndex + 1)) / visibleCount
            visibleIndex++
            CharacterTiming(ch, start, end, isWhitespace = false)
        }
    }
}

/**
 * Renders syllable-by-syllable and word-by-word synchronization with radiant dynamic glow effects.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SyllableSyncLine(
    line: LyricLine,
    progressProvider: () -> Long,
    isBackground: Boolean = false,
) {
    val wordPieces = remember(line.text, line.words) {
        var at = 0
        line.words.map { word ->
            val start = line.text.indexOf(word.text, at).takeIf { it >= 0 } ?: at
            var stop = start + word.text.length
            while (stop < line.text.length && line.text[stop].isWhitespace()) stop++
            at = stop
            word to line.text.substring(start, stop)
        }
    }

    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        wordPieces.forEach { (word, piece) ->
            KaraokeWordItem(
                word = word,
                piece = piece,
                progressProvider = progressProvider,
                isBackground = isBackground,
            )
        }
    }
}

// ============================================================================
// SPICY LYRICS ANIMATION PORT
// Ported from LyricsAnimator.ts: closed-form damped springs driven by natural
// cubic-spline keyframe curves. Each word (or, for long held words, each
// letter) owns a scale / y-offset / glow spring that chases a target read off
// its spline at the current playback progress. The gradient position is a
// soft 20%-wide edge that travels from -20% to 100% of the glyph width.
// ============================================================================

/** Natural cubic spline, equivalent to the `cubic-spline` npm package's `.at(x)`. */
class NaturalCubicSpline(private val xs: FloatArray, private val ys: FloatArray) {
    private val y2 = FloatArray(xs.size)

    init {
        val n = xs.size
        val u = FloatArray(n)
        for (i in 1 until n - 1) {
            val sig = (xs[i] - xs[i - 1]) / (xs[i + 1] - xs[i - 1])
            val p = sig * y2[i - 1] + 2f
            y2[i] = (sig - 1f) / p
            val d = (ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i]) - (ys[i] - ys[i - 1]) / (xs[i] - xs[i - 1])
            u[i] = (6f * d / (xs[i + 1] - xs[i - 1]) - sig * u[i - 1]) / p
        }
        y2[n - 1] = 0f
        for (k in n - 2 downTo 0) y2[k] = y2[k] * y2[k + 1] + u[k]
    }

    fun at(x: Float): Float {
        var lo = 0
        var hi = xs.size - 1
        while (hi - lo > 1) {
            val mid = (hi + lo) / 2
            if (xs[mid] > x) hi = mid else lo = mid
        }
        val h = xs[hi] - xs[lo]
        val a = (xs[hi] - x) / h
        val b = (x - xs[lo]) / h
        return a * ys[lo] + b * ys[hi] +
            ((a * a * a - a) * y2[lo] + (b * b * b - b) * y2[hi]) * (h * h) / 6f
    }
}

/**
 * Frame-rate independent damped spring (closed-form solution).
 */
class LyricSpring(initial: Float, private val frequency: Float, private val damping: Float) {
    private var position = initial
    private var velocity = 0f
    private var goal = initial

    fun setGoal(newGoal: Float, instant: Boolean = false) {
        goal = newGoal
        if (instant) {
            position = newGoal
            velocity = 0f
        }
    }

    fun step(dt: Float): Float {
        if (dt <= 0f) return position
        val w = frequency * 2f * Math.PI.toFloat()
        val offset = position - goal
        val decay = exp(-damping * w * dt)
        if (damping < 1f) {
            val c = sqrt(1f - damping * damping)
            val i = cos(w * c * dt)
            val j = sin(w * c * dt)
            val y = j / c
            val z = j / (w * c)
            position = (offset * (i + damping * y) + velocity * z) * decay + goal
            velocity = (velocity * (i - damping * y) - offset * (w * y)) * decay
        } else {
            position = (velocity * dt + offset * (1f + w * dt)) * decay + goal
            velocity = (velocity - w * dt * (velocity + w * offset)) * decay
        }
        return position
    }
}

private val WordScaleSpline = NaturalCubicSpline(floatArrayOf(0f, 0.7f, 1f), floatArrayOf(0.95f, 1.0505f, 1f))
private val WordYOffsetSpline = NaturalCubicSpline(floatArrayOf(0f, 0.9f, 1f), floatArrayOf(1f / 100f, -(1f / 60f), 0f))
private val LetterScaleSpline = NaturalCubicSpline(floatArrayOf(0f, 0.7f, 1f), floatArrayOf(0.95f, 1.175f, 1f))
private val LetterYOffsetSpline = NaturalCubicSpline(floatArrayOf(0f, 0.9f, 1f), floatArrayOf(1f / 100f, -(1f / 56f), 0f))
private val GlowSpline = NaturalCubicSpline(floatArrayOf(0f, 0.15f, 0.6f, 1f), floatArrayOf(0f, 1f, 1f, 0f))

private const val SUNG_LETTER_GLOW = 0.2f
private const val LIT_ALPHA = 0.85f
private const val UNLIT_ALPHA = 0.35f
private const val GRADIENT_SOFT_EDGE = 0.2f
private const val GRADIENT_REST = -0.2f
private const val LETTER_GROUP_MIN_MS = 1000L

@Stable
class GlyphAnimState(initialScale: Float, initialYOffset: Float) {
    val scale = mutableFloatStateOf(initialScale)
    val yOffset = mutableFloatStateOf(initialYOffset)
    val glow = mutableFloatStateOf(0f)
    val gradient = mutableFloatStateOf(GRADIENT_REST)
}

private class GlyphSprings(scale: Float, yOffset: Float, glow: Float) {
    val scale = LyricSpring(scale, 0.88f, 0.64f)
    val yOffset = LyricSpring(yOffset, 1.45f, 0.4f)
    val glow = LyricSpring(glow, 1.18f, 0.56f)
}

private fun phaseOf(now: Long, start: Long, end: Long): Int = when {
    now < start -> 0
    now >= end -> 2
    else -> 1
}

private fun progressOf(now: Long, start: Long, end: Long): Float = when {
    now <= start -> 0f
    now >= end -> 1f
    else -> (now - start).toFloat() / (end - start).toFloat()
}

@Composable
fun KaraokeWordItem(
    word: LyricWord,
    piece: String,
    progressProvider: () -> Long,
    isBackground: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val visualSettings = LocalLyricVisualSettings.current
    val charTimings = remember(word, piece) { calculateCharacterTimings(word, piece) }
    val isLetterGroup = remember(word, piece) {
        charTimings.count { !it.isWhitespace } > 1 &&
            (word.endMs - word.startMs) >= LETTER_GROUP_MIN_MS
    }
    val jumpPadding = (12.dp * visualSettings.jumpStrength).coerceAtLeast(8.dp)

    if (isLetterGroup) {
        KaraokeLetterGroup(
            word = word,
            charTimings = charTimings,
            progressProvider = progressProvider,
            jumpPadding = jumpPadding,
            isBackground = isBackground,
            modifier = modifier,
        )
    } else {
        KaraokePlainWord(
            word = word,
            piece = piece,
            progressProvider = progressProvider,
            jumpPadding = jumpPadding,
            isBackground = isBackground,
            modifier = modifier,
        )
    }
}

@Composable
private fun GradientGlyphText(
    text: String,
    style: TextStyle,
    anim: GlyphAnimState,
    isBackground: Boolean = false,
) {
    val litAlpha = if (isBackground) 0.60f else LIT_ALPHA
    val unlitAlpha = if (isBackground) 0.30f else UNLIT_ALPHA

    Text(
        text = text,
        color = Color.White,
        softWrap = false,
        style = style,
        modifier = Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val w = size.width
                if (w > 0f) {
                    val start = anim.gradient.floatValue * w
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = litAlpha),
                                Color.Black.copy(alpha = unlitAlpha),
                            ),
                            startX = start,
                            endX = start + w * GRADIENT_SOFT_EDGE,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
            },
    )
}

@Composable
private fun GlowGlyphText(
    text: String,
    style: TextStyle,
    anim: GlyphAnimState,
    alphaPerGlow: Float,
    blurBase: Float,
    blurPerGlow: Float,
) {
    val visualSettings = LocalLyricVisualSettings.current
    val density = LocalDensity.current.density
    val glowStep by remember(anim) {
        derivedStateOf { (anim.glow.floatValue.coerceAtLeast(0f) * 20f).roundToInt() }
    }
    if (glowStep > 0) {
        val g = glowStep / 20f
        Text(
            text = text,
            color = Color.Transparent,
            softWrap = false,
            style = style.copy(
                shadow = Shadow(
                    color = Color.White.copy(
                        alpha = (g * alphaPerGlow * visualSettings.glowIntensity).coerceIn(0f, 1f),
                    ),
                    offset = Offset.Zero,
                    blurRadius = (blurBase + blurPerGlow * g) * density * visualSettings.glowRadius,
                ),
            ),
        )
    }
}

@Composable
private fun lyicTextStyle(isBackground: Boolean = false): TextStyle {
    val visualSettings = LocalLyricVisualSettings.current
    val baseSize = if (isBackground) 18f else 27f
    val baseLineHeight = if (isBackground) 24f else 33f
    return MaterialTheme.typography.headlineSmall.copy(
        fontWeight = if (isBackground) FontWeight.SemiBold else FontWeight.Bold,
        fontSize = (baseSize * visualSettings.lyricsSize).sp,
        lineHeight = (baseLineHeight * visualSettings.lyricsSize).sp,
    )
}

@Composable
private fun KaraokePlainWord(
    word: LyricWord,
    piece: String,
    progressProvider: () -> Long,
    jumpPadding: Dp,
    isBackground: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val visualSettings = LocalLyricVisualSettings.current
    val style = lyicTextStyle(isBackground = isBackground)
    val anim = remember(word) {
        GlyphAnimState(WordScaleSpline.at(0f), WordYOffsetSpline.at(0f))
    }
    val progress by rememberUpdatedState(progressProvider)

    LaunchedEffect(word) {
        val springs = GlyphSprings(WordScaleSpline.at(0f), WordYOffsetSpline.at(0f), GlowSpline.at(0f))
        var last = 0L
        while (true) {
            withFrameNanos { nanos ->
                val first = last == 0L
                val dt = if (first) 0f else ((nanos - last) / 1_000_000_000f).coerceAtMost(0.05f)
                last = nanos

                val now = progress()
                val pct = progressOf(now, word.startMs, word.endMs)
                val at = when (phaseOf(now, word.startMs, word.endMs)) {
                    0 -> 0f
                    2 -> 1f
                    else -> pct
                }
                springs.scale.setGoal(WordScaleSpline.at(at), first)
                springs.yOffset.setGoal(WordYOffsetSpline.at(at), first)
                springs.glow.setGoal(GlowSpline.at(at), first)

                anim.scale.floatValue = springs.scale.step(dt)
                anim.yOffset.floatValue = springs.yOffset.step(dt)
                anim.glow.floatValue = springs.glow.step(dt)
                anim.gradient.floatValue = when (phaseOf(now, word.startMs, word.endMs)) {
                    0 -> GRADIENT_REST
                    2 -> 1f
                    else -> GRADIENT_REST + 1.2f * pct
                }
            }
        }
    }

    val text = remember(piece) { piece.trimEnd() }
    val space = remember(piece) { piece.substring(text.length) }

    Row(modifier = modifier.wrapContentWidth()) {
        if (text.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .overflowBounds(jumpPadding)
                    .graphicsLayer {
                        val baseSize = if (isBackground) 18f else 27f
                        val fontPx = (baseSize * visualSettings.lyricsSize).sp.toPx()
                        val scaleFactor = if (isBackground) 0.85f else 1.0f
                        scaleX = anim.scale.floatValue * scaleFactor
                        scaleY = anim.scale.floatValue * scaleFactor
                        translationY = anim.yOffset.floatValue * fontPx
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        clip = false
                    }
                    .padding(jumpPadding),
                contentAlignment = Alignment.Center,
            ) {
                GlowGlyphText(text, style, anim, alphaPerGlow = if (isBackground) 0.25f else 0.35f, blurBase = 4f, blurPerGlow = 2f)
                GradientGlyphText(text, style, anim, isBackground = isBackground)
            }
        }
        if (space.isNotEmpty()) {
            Text(
                text = space,
                color = Color.Transparent,
                softWrap = false,
                style = style,
            )
        }
    }
}

@Composable
private fun KaraokeLetterGroup(
    word: LyricWord,
    charTimings: List<CharacterTiming>,
    progressProvider: () -> Long,
    jumpPadding: Dp,
    isBackground: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val visualSettings = LocalLyricVisualSettings.current
    val wordAnim = remember(word) {
        GlyphAnimState(WordScaleSpline.at(0f), WordYOffsetSpline.at(0f))
    }
    val letterAnims = remember(charTimings) {
        charTimings.map { GlyphAnimState(LetterScaleSpline.at(0f), LetterYOffsetSpline.at(0f)) }
    }
    val progress by rememberUpdatedState(progressProvider)

    LaunchedEffect(charTimings) {
        val wordSprings = GlyphSprings(WordScaleSpline.at(0f), WordYOffsetSpline.at(0f), GlowSpline.at(0f))
        val letterSprings = charTimings.map {
            GlyphSprings(LetterScaleSpline.at(0f), LetterYOffsetSpline.at(0f), GlowSpline.at(0f))
        }
        val restScale = LetterScaleSpline.at(0f)
        val restY = LetterYOffsetSpline.at(0f)
        val restGlow = GlowSpline.at(0f)
        var last = 0L

        while (true) {
            withFrameNanos { nanos ->
                val first = last == 0L
                val dt = if (first) 0f else ((nanos - last) / 1_000_000_000f).coerceAtMost(0.05f)
                last = nanos

                val now = progress()
                val wordPhase = phaseOf(now, word.startMs, word.endMs)
                val wordAt = when (wordPhase) {
                    0 -> 0f
                    2 -> 1f
                    else -> progressOf(now, word.startMs, word.endMs)
                }
                wordSprings.scale.setGoal(WordScaleSpline.at(wordAt), first)
                wordSprings.yOffset.setGoal(WordYOffsetSpline.at(wordAt), first)
                wordAnim.scale.floatValue = wordSprings.scale.step(dt)
                wordAnim.yOffset.floatValue = wordSprings.yOffset.step(dt)

                var activeIdx = -1
                var activePct = 0f
                if (wordPhase == 1) {
                    for (i in charTimings.indices) {
                        val t = charTimings[i]
                        if (!t.isWhitespace && phaseOf(now, t.startMs, t.endMs) == 1) {
                            activeIdx = i
                            activePct = progressOf(now, t.startMs, t.endMs)
                            break
                        }
                    }
                }

                for (k in charTimings.indices) {
                    val t = charTimings[k]
                    if (t.isWhitespace) continue

                    var tScale = restScale
                    var tY = restY
                    var tGlow = restGlow
                    var tGrad = GRADIENT_REST

                    when (wordPhase) {
                        0 -> Unit
                        2 -> {
                            tScale = LetterScaleSpline.at(1f)
                            tY = LetterYOffsetSpline.at(1f)
                            tGlow = GlowSpline.at(1f)
                            tGrad = 1f
                        }
                        else -> {
                            val letterPhase = phaseOf(now, t.startMs, t.endMs)
                            if (activeIdx != -1) {
                                val d = abs(k - activeIdx).toFloat()
                                val falloff = 1f / (1f + d.pow(2.8f))
                                val glowFalloff = 1f / (1f + d * 0.9f)
                                tScale = restScale + (LetterScaleSpline.at(activePct) - restScale) * falloff
                                tY = restY + (LetterYOffsetSpline.at(activePct) - restY) * falloff
                                tGlow = restGlow + (GlowSpline.at(activePct) - restGlow) * glowFalloff
                            }
                            if (letterPhase == 0) {
                                tScale = restScale
                                tY = restY
                                tGlow = restGlow
                            } else if (letterPhase == 2 && activeIdx == -1) {
                                tGlow = GlowSpline.at(SUNG_LETTER_GLOW)
                            }
                            tGrad = when (letterPhase) {
                                0 -> GRADIENT_REST
                                2 -> 1f
                                else -> if (k == activeIdx) {
                                    GRADIENT_REST + 1.2f * sin(activePct * (Math.PI.toFloat() / 2f))
                                } else GRADIENT_REST
                            }
                        }
                    }

                    val springs = letterSprings[k]
                    springs.scale.setGoal(tScale, first)
                    springs.yOffset.setGoal(tY, first)
                    springs.glow.setGoal(tGlow, first)

                    val anim = letterAnims[k]
                    anim.scale.floatValue = springs.scale.step(dt)
                    anim.yOffset.floatValue = springs.yOffset.step(dt)
                    anim.glow.floatValue = springs.glow.step(dt)
                    anim.gradient.floatValue = tGrad
                }
            }
        }
    }

    Row(
        modifier = modifier
            .wrapContentWidth()
            .overflowBounds(jumpPadding)
            .graphicsLayer {
                val baseSize = if (isBackground) 18f else 27f
                val fontPx = (baseSize * visualSettings.lyricsSize).sp.toPx()
                val scaleFactor = if (isBackground) 0.85f else 1.0f
                scaleX = wordAnim.scale.floatValue * scaleFactor
                scaleY = wordAnim.scale.floatValue * scaleFactor
                translationY = wordAnim.yOffset.floatValue * fontPx
                transformOrigin = TransformOrigin(0f, 0.5f)
                clip = false
            }
            .padding(jumpPadding),
    ) {
        charTimings.forEachIndexed { index, timing ->
            KaraokeLetterItem(
                timing = timing,
                anim = letterAnims[index],
                isBackground = isBackground,
            )
        }
    }
}

@Composable
fun KaraokeLetterItem(
    timing: CharacterTiming,
    anim: GlyphAnimState,
    isBackground: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val visualSettings = LocalLyricVisualSettings.current
    val style = lyicTextStyle(isBackground = isBackground)

    if (timing.isWhitespace) {
        Text(
            text = timing.char.toString(),
            color = Color.Transparent,
            softWrap = false,
            style = style,
        )
        return
    }

    val jumpPadding = (12.dp * visualSettings.jumpStrength).coerceAtLeast(8.dp)
    val text = remember(timing) { timing.char.toString() }

    Box(
        modifier = modifier
            .wrapContentWidth()
            .overflowBounds(jumpPadding)
            .graphicsLayer {
                val baseSize = if (isBackground) 18f else 27f
                val fontPx = (baseSize * visualSettings.lyricsSize).sp.toPx()
                val scaleFactor = if (isBackground) 0.85f else 1.0f
                scaleX = anim.scale.floatValue * scaleFactor
                scaleY = anim.scale.floatValue * scaleFactor
                translationY = anim.yOffset.floatValue * 2f * fontPx
                transformOrigin = TransformOrigin(0f, 0.85f)
                clip = false
            }
            .padding(jumpPadding),
        contentAlignment = Alignment.Center,
    ) {
        GlowGlyphText(text, style, anim, alphaPerGlow = if (isBackground) 1.25f else 1.85f, blurBase = 4f, blurPerGlow = 12f)
        GradientGlyphText(text, style, anim, isBackground = isBackground)
    }
}

/**
 * Three-dot interlude item for instrumental gaps during the song.
 */
@Composable
fun InterludeDotsItem(
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val springSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessLow,
    )

    val scale by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.90f,
        animationSpec = springSpec,
        label = "interludeScale",
    )

    val alpha by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.35f,
        animationSpec = springSpec,
        label = "interludeAlpha",
    )

    val infiniteTransition = rememberInfiniteTransition(label = "interludePulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0.5f)
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            },
        ) {
            PulsingGlowDot(
                isActive = isActive,
                activeColor = Color.White,
                pulseAlpha = pulseAlpha,
            )
            PulsingGlowDot(
                isActive = isActive,
                activeColor = Color.White,
                pulseAlpha = pulseAlpha,
            )
            PulsingGlowDot(
                isActive = isActive,
                activeColor = Color.White,
                pulseAlpha = pulseAlpha,
            )
        }
    }
}

@Composable
fun PulsingGlowDot(
    isActive: Boolean,
    activeColor: Color,
    pulseAlpha: Float,
) {
    val visualSettings = LocalLyricVisualSettings.current

    val alpha by animateFloatAsState(
        targetValue = if (isActive) pulseAlpha else 0.35f,
        animationSpec = tween(600),
        label = "dotAlpha",
    )

    Box(
        modifier = Modifier.size(13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .requiredSize(61.dp)
                .graphicsLayer {
                    this.alpha = alpha
                }
                .drawBehind {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val coreRadius = 13.dp.toPx() / 2f

                    if (isActive && visualSettings.glowIntensity > 0f) {
                        val blurRadius = 15f * visualSettings.glowIntensity * visualSettings.lyricsSize
                        drawIntoCanvas { canvas ->
                            val paint = Paint().asFrameworkPaint().apply {
                                color = activeColor.toArgb()
                                setAlpha((255 * 0.75f * visualSettings.glowIntensity * alpha).toInt().coerceIn(0, 255))
                                maskFilter = android.graphics.BlurMaskFilter(
                                    blurRadius.coerceAtLeast(1f),
                                    android.graphics.BlurMaskFilter.Blur.NORMAL,
                                )
                            }
                            canvas.nativeCanvas.drawCircle(
                                center.x,
                                center.y,
                                coreRadius + (blurRadius / 4f),
                                paint,
                            )
                        }
                    }

                    drawCircle(
                        color = activeColor,
                        radius = coreRadius,
                        center = center,
                    )
                },
        )
    }
}

/** Rough line height in px, used to bias the auto-scroll target. */
private const val LineHeightPx = 60
