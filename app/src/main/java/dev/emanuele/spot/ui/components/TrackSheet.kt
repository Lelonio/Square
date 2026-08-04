package dev.emanuele.spot.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import dev.emanuele.spot.ui.player.GlassSurface
import dev.emanuele.spot.ui.theme.softShadow

/**
 * What can be done with one track, as a modal.
 *
 * It went through a dropdown and then a row of icons first. Both were wrong for
 * the same reason: the actions here are about a *particular* track, and a menu
 * floating beside a list of fifty rows never says which one. The cover and the
 * title at the top of a sheet do, and are the only part of this that is not a
 * button.
 *
 * The page behind it is blurred rather than merely dimmed. A scrim darkens the
 * page and leaves it perfectly readable underneath, which is what makes a modal
 * feel like a panel resting on a screen you are still using. Out of focus, the
 * screen stops being something to read and the sheet is the only thing left.
 *
 * Must be drawn above the tab bar and the now-playing bar — that is, at app
 * level and not inside a screen, since those are drawn over the whole
 * navigation host.
 */
@Composable
fun BoxScope.TrackSheet(
    visible: Boolean,
    title: String,
    subtitle: String,
    artworkUrl: String?,
    /** The layer the page is recorded into; blurred behind the sheet. */
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
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
                // The whole page, out of focus. Drawn from the recorded layer
                // rather than with `Modifier.blur` on the content itself, which
                // would blur the screen for everyone including the sheet.
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { androidx.compose.ui.graphics.RectangleShape },
                    effects = { blur(28f.dp.toPx()) },
                    onDrawSurface = { drawRect(Color.Black.copy(alpha = 0.42f)) },
                )
                .clickable(interactionSource = null, indication = null, onClick = onDismiss),
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(tween(220), initialScale = 0.9f) + fadeIn(tween(160)),
        exit = scaleOut(tween(160), targetScale = 0.94f) + fadeOut(tween(140)),
        modifier = Modifier.align(Alignment.Center),
    ) {
        val shape = RoundedCornerShape(30.dp)
        GlassSurface(
            backdrop = backdrop,
            shape = shape,
            surfaceColor = SheetFilm,
            // Heavier than the page behind it, on purpose. The panel samples the
            // *unblurred* layer, so at the usual 8dp the rows underneath came
            // through sharper inside the sheet than outside it — the one thing
            // that reads as a mistake rather than as a material.
            blurRadius = 46.dp,
            modifier = Modifier
                .padding(horizontal = 28.dp)
                .fillMaxWidth()
                .clip(shape)
                .softShadow(shape, elevation = 26.dp, spot = 0.35f)
                // Swallows the tap that would otherwise close the sheet.
                .clickable(interactionSource = null, indication = null) {},
        ) {
            Column(Modifier.padding(vertical = 22.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(
                        url = artworkUrl,
                        title = title,
                        modifier = Modifier.size(58.dp),
                        corner = 12.dp,
                        decodeSize = 58.dp,
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = 16.dp),
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.66f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Box(
                    Modifier
                        .padding(top = 16.dp, bottom = 6.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp)
                        .size(width = 0.dp, height = 1.dp)
                        .background(Color.White.copy(alpha = 0.12f)),
                )

                actions()
            }
        }
    }
}

@Composable
fun TrackSheetAction(
    label: String,
    icon: ImageVector,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else Color.White
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

/** Light: the page behind is already blurred, so this only has to be a surface. */
private val SheetFilm = Color.White.copy(alpha = 0.16f)
