package dev.emanuele.spot.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Scale
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * Square artwork, falling back to a generated cover.
 *
 * The fallback is derived from the title rather than one grey placeholder: a
 * wall of identical squares makes a list unreadable, while a stable per-title
 * colour gives each row something to recognise.
 */
@Composable
fun Artwork(
    url: String?,
    title: String,
    modifier: Modifier = Modifier,
    corner: Dp = 10.dp,
    /**
     * Decode size. Covers arrive at up to 640px; decoding one of those into a
     * 48dp row costs both memory and time on the scrolling frame, and a hundred
     * rows of it is what makes a list stutter.
     */
    decodeSize: Dp = 0.dp,
) {
    val shape = remember(corner) { RoundedCornerShape(corner) }
    val context = LocalContext.current
    val density = LocalDensity.current

    Box(modifier.clip(shape), contentAlignment = Alignment.Center) {
        if (url != null) {
            val request = remember(url, decodeSize) {
                ImageRequest.Builder(context)
                    .data(url)
                    .scale(Scale.FILL)
                    .apply {
                        if (decodeSize > 0.dp) {
                            val px = with(density) { decodeSize.toPx() }.roundToInt()
                            size(px, px)
                        }
                    }
                    // No crossfade: it animates every row that scrolls into view,
                    // which is exactly when frames are scarcest.
                    .crossfade(false)
                    .build()
            }

            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            GeneratedCover(title, corner)
        }
    }
}

/**
 * The cover for something that has none.
 *
 * The previous version picked a saturated hue per title. It did make rows
 * distinguishable, but it put a random colour next to real album art in a
 * palette that is otherwise neutral, so the placeholders were the loudest thing
 * on a screen despite being the least important. This keeps the same idea —
 * stable and different per title — and spends it on tone and geometry instead:
 * a dark neutral gradient whose angle and lightness come from the title, one
 * soft off-centre highlight, and the initial set large and dimmed so it reads as
 * texture rather than as a label.
 */
@Composable
private fun GeneratedCover(title: String, corner: Dp) {
    val seed = remember(title) { title.hashCode().absoluteValue }
    val initial = remember(title) {
        title.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "♪"
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            // Four fixed diagonals rather than a free angle: a gradient that can
            // land at any rotation makes a grid of these look accidental.
            val direction = seed % 4
            val start = when (direction) {
                0 -> Offset(0f, 0f)
                1 -> Offset(size.width, 0f)
                2 -> Offset(0f, size.height)
                else -> Offset(size.width, size.height)
            }
            val end = Offset(size.width - start.x, size.height - start.y)

            // Narrow range: these have to sit together in a row without one
            // looking like a hole and the next like a light box.
            val lightness = 0.16f + (seed / 4 % 5) * 0.035f

            drawRect(
                Brush.linearGradient(
                    listOf(
                        Color.hsl(220f, 0.06f, lightness + 0.07f),
                        Color.hsl(230f, 0.08f, lightness),
                    ),
                    start = start,
                    end = end,
                ),
            )

            // A single diffuse highlight, placed off centre so the square does
            // not read as flat fill.
            drawRect(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(
                        size.width * (0.25f + (seed / 20 % 4) * 0.17f),
                        size.height * 0.22f,
                    ),
                    radius = size.minDimension * 0.85f,
                ),
            )
        }

        Text(
            text = initial,
            style = MaterialTheme.typography.displayLarge,
            // Sized to the tile: the same absolute type looks like a label on a
            // large cover and fills a 46dp row edge to edge.
            fontSize = with(LocalDensity.current) { (sizeHint(corner)).toSp() },
            color = Color.White.copy(alpha = 0.20f),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Type size for the initial, inferred from the corner radius.
 *
 * A hack, and a deliberate one: the alternative is threading a size through
 * every call site, and corner radius already scales with the tile everywhere
 * this is used.
 */
private fun sizeHint(corner: Dp): Dp = (corner * 2.6f).coerceIn(16.dp, 96.dp)
