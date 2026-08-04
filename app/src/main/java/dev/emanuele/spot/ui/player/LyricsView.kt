package dev.emanuele.spot.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.emanuele.spot.data.Lyrics
import kotlin.math.abs

/**
 * Synced lyrics, animated in the manner of Apple Music's view — the approach
 * demonstrated by [amlv](https://github.com/dokar3/amlv) (Apache-2.0), written
 * here rather than adapted.
 *
 * Three things carry the effect, and they matter more together than separately:
 * the active line grows and brightens, lines fall away by *distance* from it
 * rather than being uniformly dim, and everything moves on a spring so a line
 * change reads as physical instead of as a cut.
 */
@Composable
fun LyricsView(
    lyrics: Lyrics,
    positionMs: State<Long>,
    modifier: Modifier = Modifier,
    onSeek: (Long) -> Unit,
) {
    val listState = rememberLazyListState()
    val position = positionMs.value

    val activeLine = remember(lyrics, position) {
        if (!lyrics.synced) -1
        else lyrics.lines.indexOfLast { (it.startTimeMs ?: 0L) <= position }
    }

    // Centre the active line rather than pin it to the top: the lines around it
    // are the context that makes a lyric readable while it plays.
    androidx.compose.runtime.LaunchedEffect(activeLine) {
        if (activeLine < 0) return@LaunchedEffect
        val viewportCentre = listState.layoutInfo.viewportSize.height / 2
        listState.animateScrollToItem(activeLine, -viewportCentre + LineHeightPx)
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            // Fades the ends instead of cutting lines off square. DstIn needs
            // its own layer, otherwise the mask would erase what is behind the
            // list as well.
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 40.dp),
    ) {
        itemsIndexed(lyrics.lines) { index, line ->
            val isActive = index == activeLine
            LyricRow(
                text = line.text,
                distance = if (activeLine < 0) 0 else abs(index - activeLine),
                isActive = isActive,
                unsynced = !lyrics.synced,
                // Only the line being sung pays for this; the rest are drawn
                // plain.
                sungFraction = if (isActive) {
                    lineProgress(lyrics, index, position)
                } else {
                    0f
                },
                onClick = { line.startTimeMs?.let(onSeek) },
            )
        }
    }
}

/**
 * How far through the current line playback is, 0 to 1.
 *
 * The line's end is the next line's start; the last line has no such marker, so
 * it gets a fixed span rather than staying at zero for however long the outro
 * runs.
 */
private fun lineProgress(lyrics: Lyrics, index: Int, positionMs: Long): Float {
    val start = lyrics.lines[index].startTimeMs ?: return 0f
    val end = lyrics.lines.getOrNull(index + 1)?.startTimeMs ?: (start + TAIL_LINE_MS)
    val span = (end - start).coerceAtLeast(1L)
    return ((positionMs - start).toFloat() / span).coerceIn(0f, 1f)
}

/**
 * Splits a line into words with a share of its duration each.
 *
 * Spotify times lyrics by line, never by word, so Apple Music's sung-along
 * highlight cannot be reproduced from the data — this approximates it by giving
 * each word a share of the line proportional to its length. Longer words take
 * longer to sing, which is true often enough to read as right; it will drift on
 * a line that holds one syllable for a bar, and nothing here can know that.
 *
 * Trailing space is counted with the word so the highlight sweeps continuously
 * instead of stepping across gaps.
 */
private fun wordStops(text: String): List<Pair<IntRange, Float>> {
    val total = text.count { !it.isWhitespace() }.coerceAtLeast(1)
    val stops = mutableListOf<Pair<IntRange, Float>>()
    var consumed = 0
    var index = 0
    while (index < text.length) {
        while (index < text.length && text[index].isWhitespace()) index++
        if (index >= text.length) break
        val start = index
        while (index < text.length && !text[index].isWhitespace()) index++
        val wordEnd = index
        while (index < text.length && text[index].isWhitespace()) index++
        consumed += wordEnd - start
        stops += (start until index) to consumed.toFloat() / total
    }
    return stops
}

@Composable
private fun LyricRow(
    text: String,
    distance: Int,
    isActive: Boolean,
    unsynced: Boolean,
    sungFraction: Float,
    onClick: () -> Unit,
) {
    val springSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessLow,
    )

    val scale by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.90f,
        animationSpec = springSpec,
        label = "lyricScale",
    )

    // Opacity falls off with distance, so the eye is pulled to the current line
    // without the rest disappearing.
    val alpha by animateFloatAsState(
        targetValue = when {
            unsynced -> 1f
            isActive -> 1f
            else -> (0.62f - distance * 0.12f).coerceAtLeast(0.22f)
        },
        animationSpec = springSpec,
        label = "lyricAlpha",
    )

    // Blur only kicks in a few lines out, and only where the platform supports
    // it (API 31+); below that it is a no-op and the alpha ramp carries it.
    val blurRadius = when {
        unsynced || distance <= 1 -> 0.dp
        else -> ((distance - 1) * 1.1f).coerceAtMost(4.5f).dp
    }

    Box(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !unsynced, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 9.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        // The sung part of the line is fully lit, the rest of it is not.
        //
        // Word by word rather than a sweep across the whole line: a gradient
        // spans the text block, so on a line that wraps it would light the
        // second row from the left while the first is still being sung.
        val rendered = remember(text, isActive, sungFraction) {
            if (!isActive) {
                AnnotatedString(text)
            } else {
                buildAnnotatedString {
                    append(text)
                    wordStops(text).forEach { (range, stop) ->
                        if (stop > sungFraction) {
                            addStyle(
                                SpanStyle(color = Color.White.copy(alpha = 0.45f)),
                                range.first,
                                range.last + 1,
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = rendered,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 27.sp, lineHeight = 33.sp),
            fontWeight = FontWeight.Bold,
            // White rather than the artwork accent. These sit over a Canvas now,
            // and an accent pulled from the cover can land anywhere — including
            // on the clip's own colours.
            color = Color.White,
            textAlign = TextAlign.Start,
            modifier = Modifier
                .then(if (blurRadius > 0.dp) Modifier.blur(blurRadius) else Modifier)
                .graphicsLayer {
                    // Scale from the left edge so the text grows into the line
                    // instead of drifting sideways.
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                },
        )
    }
}

/** Rough line height in px, used to bias the auto-scroll target. */
private const val LineHeightPx = 60

/** How long the last line is assumed to last, having no next line to end it. */
private const val TAIL_LINE_MS = 4_000L
