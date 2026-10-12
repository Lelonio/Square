package dev.lelonio.square.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lelonio.square.data.LyricLine
import dev.lelonio.square.data.Lyrics
import kotlin.math.abs

/**
 * The lyrics as lines, plainly: the one being sung in full ink, the others
 * quieter, and nothing else moving.
 *
 * The other style, [LyricsView], is the animated one ported from Spicy Lyrics,
 * with words that glow and lift as they are sung. This is for whoever prefers
 * to read them still, and for phones the animation is heavy on: the words of
 * the line being sung take the ink in turn, where the source times them, and
 * nothing else moves.
 */
@Composable
fun ClassicLyricsView(
    lyrics: Lyrics,
    positionMs: State<Long>,
    modifier: Modifier = Modifier,
    showTranslation: Boolean = false,
    onSeek: (Long) -> Unit,
) {
    val listState = remember(lyrics) { LazyListState() }
    // Read as a derived value, so only a change of line recomposes the list.
    val activeLine by remember(lyrics) {
        derivedStateOf {
            if (!lyrics.synced) -1
            else lyrics.lines.indexOfLast { (it.startTimeMs ?: 0L) <= positionMs.value }
        }
    }

    LaunchedEffect(activeLine) {
        if (activeLine < 0) return@LaunchedEffect
        val layout = listState.layoutInfo
        val viewport = layout.viewportSize.height
        if (viewport <= 0) return@LaunchedEffect
        val item = layout.visibleItemsInfo.find { it.index == activeLine }
        if (item == null) {
            listState.animateScrollToItem(activeLine, -viewport / 3)
        } else {
            val delta = item.offset + item.size / 2 - viewport / 2
            if (abs(delta) > 4) {
                listState.animateScrollBy(delta.toFloat(), tween(420, easing = FastOutSlowInEasing))
            }
        }
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(vertical = 48.dp),
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.15f to Color.Black,
                        0.85f to Color.Black,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        itemsIndexed(lyrics.lines) { index, line ->
            val sung = !lyrics.synced || index == activeLine
            val ink by animateColorAsState(
                targetValue = if (sung) GlassInk else GlassInk.copy(alpha = 0.38f),
                animationSpec = tween(300),
                label = "classicLine",
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = line.startTimeMs != null) { line.startTimeMs?.let(onSeek) }
                    .padding(horizontal = 24.dp, vertical = 2.dp),
            ) {
                // The line being sung, word by word where the source times
                // its words: what has been sung in full ink, the word being
                // sung brightening as it goes, the rest as quiet as the
                // lines around it. Only this line reads the position, so
                // only it redraws as the song moves.
                val timed = lyrics.synced && index == activeLine && line.words.isNotEmpty()
                val ranges = remember(line) { wordRanges(line) }
                Text(
                    if (timed) {
                        sungSoFar(line, ranges, positionMs.value, GlassInk, GlassInk.copy(alpha = 0.38f))
                    } else {
                        AnnotatedString(line.text.ifBlank { "♪" })
                    },
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = 24.sp,
                        lineHeight = 31.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = if (timed) GlassInk.copy(alpha = 0.38f) else ink,
                )
                val translation = line.translation.takeIf { showTranslation && !it.isNullOrBlank() }
                if (translation != null) {
                    Text(
                        translation,
                        style = MaterialTheme.typography.bodyLarge,
                        color = ink.copy(alpha = ink.alpha * 0.75f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** Where each timed word sits in the line's text, found in order; null for one not found. */
private fun wordRanges(line: LyricLine): List<IntRange?> {
    var cursor = 0
    return line.words.map { word ->
        val at = line.text.indexOf(word.text, cursor)
        if (at < 0) {
            null
        } else {
            cursor = at + word.text.length
            at until cursor
        }
    }
}

/** The line, coloured as far as it has been sung at [positionMs]. */
private fun sungSoFar(
    line: LyricLine,
    ranges: List<IntRange?>,
    positionMs: Long,
    sung: Color,
    quiet: Color,
): AnnotatedString =
    buildAnnotatedString {
        append(line.text)
        // Everything up to the end of the last word sung, the spaces too.
        val sungEnd = line.words.indices
            .lastOrNull { line.words[it].endMs <= positionMs && ranges[it] != null }
            ?.let { ranges[it]!!.last + 1 } ?: 0
        if (sungEnd > 0) addStyle(SpanStyle(color = sung), 0, sungEnd)
        val singing = line.words.indices.firstOrNull {
            val word = line.words[it]
            positionMs in word.startMs until word.endMs && ranges[it] != null
        } ?: return@buildAnnotatedString
        val word = line.words[singing]
        val range = ranges[singing]!!
        val progress = ((positionMs - word.startMs).toFloat() / (word.endMs - word.startMs).coerceAtLeast(1))
            .coerceIn(0f, 1f)
        addStyle(SpanStyle(color = lerp(quiet, sung, progress)), range.first, range.last + 1)
    }
