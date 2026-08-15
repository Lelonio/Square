package dev.lelonio.square.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.regular.Plus
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import dev.lelonio.square.R
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.ui.glass.pressable
import dev.lelonio.square.ui.player.GlassSurface
import dev.lelonio.square.ui.theme.rememberArtworkPalette
import dev.lelonio.square.ui.theme.softShadow
import androidx.compose.ui.res.stringResource

/**
 * One song, arriving from outside the app.
 *
 * The track menu would have done the job and was the wrong thing to reach for:
 * a link from a friend is not a row somebody long-pressed, and answering it with
 * the same list of chores made the app look like it had not noticed. What a
 * shared song wants is to be looked at first. The cover is the card, at the size
 * a record deserves; its own colours are what the panel glows with; and the two
 * things anybody does with a song someone sent them — hear it, or keep it — are
 * the only buttons.
 *
 * Drawn at app level like [TrackSheet], and for the same reason: it belongs over
 * the tab bar and the now-playing bar, not inside a screen.
 */
@Composable
fun BoxScope.SharedTrackCard(
    visible: Boolean,
    track: CatalogTrack?,
    backdrop: Backdrop,
    onPlay: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(180)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RectangleShape },
                    effects = { blur(30f.dp.toPx()) },
                    onDrawSurface = { drawRect(Color.Black.copy(alpha = 0.52f)) },
                )
                .clickable(interactionSource = null, indication = null, onClick = onDismiss),
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(tween(260), initialScale = 0.88f) + fadeIn(tween(180)),
        exit = scaleOut(tween(170), targetScale = 0.94f) + fadeOut(tween(140)),
        modifier = Modifier.align(Alignment.Center),
    ) {
        val shape = RoundedCornerShape(36.dp)
        val palette by rememberArtworkPalette(track?.artworkUrl)

        GlassSurface(
            backdrop = backdrop,
            shape = shape,
            surfaceColor = Color.White.copy(alpha = 0.07f),
            blurRadius = 46.dp,
            modifier = Modifier
                .padding(horizontal = 26.dp)
                .fillMaxWidth()
                .clip(shape)
                .softShadow(shape, elevation = 30.dp, spot = 0.4f)
                .clickable(interactionSource = null, indication = null) {},
        ) {
            // The record's own light, thrown on the panel behind it. Blurred
            // past any shape of its own, so what is left is colour: the card of
            // a red cover is warm and the card of a blue one is cold, without
            // either being drawn as anything.
            if (palette.isNotEmpty()) {
                Box(
                    Modifier
                        .matchParentSize()
                        .blur(60.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    palette.first().copy(alpha = 0.55f),
                                    palette.getOrElse(1) { palette.first() }.copy(alpha = 0.18f),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )
            }

            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.shared_song),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.55f),
                    letterSpacing = 1.6.sp,
                )

                Artwork(
                    url = track?.artworkUrl,
                    title = track?.name.orEmpty(),
                    modifier = Modifier
                        .padding(top = 18.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .softShadow(RoundedCornerShape(22.dp), elevation = 24.dp, spot = 0.45f),
                    corner = 22.dp,
                )

                Text(
                    track?.name.orEmpty(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 22.dp),
                )
                Text(
                    track?.artist.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                // The album, quietly. It is what tells two versions of the same
                // song apart, which is exactly the doubt a link raises.
                track?.album?.takeIf { it.isNotEmpty() && it != track.name }?.let { album ->
                    Text(
                        album,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.45f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 26.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CardButton(
                        label = stringResource(R.string.play),
                        icon = PhosphorIcons.Fill.Play,
                        filled = true,
                        onClick = onPlay,
                        modifier = Modifier.weight(1f),
                    )
                    CardButton(
                        label = stringResource(R.string.save),
                        icon = PhosphorIcons.Regular.Plus,
                        filled = false,
                        onClick = onAddToPlaylist,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * One of the two answers.
 *
 * Filled for the one most people want and clear for the other, which is the
 * whole of the hierarchy: two buttons of equal weight would make a card that
 * asks a question rather than one that offers a song.
 */
@Composable
private fun CardButton(
    label: String,
    icon: ImageVector,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier
            .clip(shape)
            .background(
                if (filled) Color.White else Color.White.copy(alpha = 0.12f),
                shape,
            )
            .pressable(onClick = onClick, pressedScale = 0.94f)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (filled) Color.Black else Color.White,
            modifier = Modifier.size(18.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (filled) Color.Black else Color.White,
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
