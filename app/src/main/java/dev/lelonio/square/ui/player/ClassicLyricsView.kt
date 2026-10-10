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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lelonio.square.data.Lyrics
import kotlin.math.abs

/**
 * The lyrics as lines, plainly: the one being sung in full ink, the others
 * quieter, and nothing else moving.
 *
 * The other style, [LyricsView], is the animated one ported from Spicy Lyrics,
 * with words lighting up as they are sung. This is for whoever prefers to read
 * them still, and for phones the animation is heavy on: a line changing colour
 * is all that is drawn when the song moves on.
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
                Text(
                    line.text.ifBlank { "♪" },
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = 24.sp,
                        lineHeight = 31.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = ink,
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
