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
            LyricRow(
                text = line.text,
                distance = if (activeLine < 0) 0 else abs(index - activeLine),
                isActive = index == activeLine,
                unsynced = !lyrics.synced,
                onClick = { line.startTimeMs?.let(onSeek) },
            )
        }
    }
}

@Composable
private fun LyricRow(
    text: String,
    distance: Int,
    isActive: Boolean,
    unsynced: Boolean,
    onClick: () -> Unit,
) {
    val springSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessLow,
    )

    val scale by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.88f,
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
        else -> ((distance - 1) * 0.9f).coerceAtMost(3.5f).dp
    }

    Box(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !unsynced, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 9.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, lineHeight = 26.sp),
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
            color = if (isActive) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
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
