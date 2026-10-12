package dev.lelonio.square.ui.browse

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The shape of a row that has not arrived yet.
 *
 * Rows read off Spotify's own browse take a moment, and until now they simply
 * appeared: a page that grows a row at a time under the reader's thumb moves
 * what is being looked at, and on a slow connection it reads as the tab having
 * finished when it has not. An outline says the same thing without lying about
 * either — this much is coming, it is not here yet.
 *
 * Breathing rather than sweeping: a highlight travelling across a placeholder is
 * a second animation on a screen whose whole business is glass, and it draws the
 * eye to the one part of the page with nothing to see.
 */
@Composable
fun SkeletonRow(
    /** How wide the tiles are, matched to the row this stands in for. */
    tile: Dp = 146.dp,
    tiles: Int = 4,
    modifier: Modifier = Modifier,
) {
    val breath = rememberInfiniteTransition(label = "skeleton")
    val alpha by breath.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(BreathMs),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonBreath",
    )

    Column(modifier.alpha(alpha)) {
        // Where the heading will be.
        Box(
            Modifier
                .padding(start = 20.dp, top = 26.dp, bottom = 12.dp)
                .width(168.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Bone),
        )
        Row(
            Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            repeat(tiles) {
                Column(Modifier.width(tile)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Bone),
                    )
                    Box(
                        Modifier
                            .padding(top = 10.dp)
                            .fillMaxWidth(0.72f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Bone),
                    )
                }
            }
        }
    }
}

/**
 * The shape of a song that has not arrived: the cover's square and two lines,
 * at the sizes a song row draws them, breathing like the rows above.
 */
@Composable
fun SkeletonTrackRow(modifier: Modifier = Modifier) {
    val breath = rememberInfiniteTransition(label = "skeletonTrack")
    val alpha by breath.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(BreathMs), repeatMode = RepeatMode.Reverse),
        label = "skeletonTrackBreath",
    )
    Row(
        modifier
            .alpha(alpha)
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Bone),
        )
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Box(
                Modifier
                    .fillMaxWidth(0.62f)
                    .height(13.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Bone),
            )
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth(0.36f)
                    .height(11.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Bone),
            )
        }
    }
}

/**
 * Barely there on purpose: this is a placeholder for something, not a thing.
 * At any more it reads as a row of blank tiles that failed to load.
 */
private val Bone = Color.White.copy(alpha = 0.07f)

private const val BreathMs = 900
