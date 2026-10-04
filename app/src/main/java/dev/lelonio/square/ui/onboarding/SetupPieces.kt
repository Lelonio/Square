package dev.lelonio.square.ui.onboarding

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.Info
import dev.lelonio.square.ui.glass.pressable
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import dev.lelonio.square.ui.theme.lightPage
import dev.lelonio.square.ui.theme.pageGround

/*
 * What the setup is drawn with: the first question and the guide after it.
 *
 * In ink, on the page's own ground, and nothing else. These screens used to
 * sit on a fixed near-black while their writing followed the phone, so in the
 * light setting they were dark words on a dark page; and every mark on them
 * was the accent, which is the colour of whatever record happens to be
 * playing, so the same guide was salmon one day and green the next.
 *
 * The app's neutral pages already say how a control looks without a colour
 * of its own, and this follows them. A card is a shade off the ground with a
 * hairline round it, white on the light side, as an unlit filter chip is. The
 * one control that moves the guide on is the ink itself, with the ground for
 * a label, as a lit chip is. Both hold in either theme without a value
 * changing side.
 */

/** The page every screen of the setup is laid on. */
@Composable
internal fun SetupScaffold(
    top: @Composable RowScope.() -> Unit,
    bottom: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        Modifier
            .fillMaxSize()
            // Opaque: nothing behind the setup is meant to show or be reached
            // while it is up, and a home page the user has not configured yet
            // would be showing them the problem rather than the way through it.
            .background(pageGround())
            .clickable(interactionSource = null, indication = null) {},
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = statusBar, bottom = navBar),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(TOP_BAR)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = top,
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                content = content,
            )
            if (bottom != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PAGE_MARGIN, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = bottom,
                )
            }
        }
    }
}

/** A shade off the ground: white on the light side, a lift out of the dark. */
@Composable
private fun cardFill(): Color = if (lightPage()) Color.White else Ink.copy(alpha = 0.07f)

@Composable
private fun cardEdge(): Color = Ink.copy(alpha = if (lightPage()) 0.07f else 0.09f)

@Composable
internal fun Modifier.setupCard(shape: Shape = CardShape): Modifier =
    this
        .clip(shape)
        .background(cardFill())
        .border(1.dp, cardEdge(), shape)

/** Running text: a step down from the ink, and still meant to be read. */
internal val Prose: Color
    @Composable get() = Ink.copy(alpha = 0.76f)

/** The control that moves the setup on: the ink, with the ground for a label. */
@Composable
internal fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .pressable({ if (enabled) onClick() }, pressedScale = if (enabled) 0.96f else 1f)
            .height(52.dp)
            .clip(CircleShape)
            .background(if (enabled) Ink else Ink.copy(alpha = 0.3f))
            .padding(horizontal = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = pageGround(),
            maxLines = 1,
        )
    }
}

/**
 * An action inside a step: logging in, linking the key.
 *
 * A card rather than a second filled button, so the page has one thing that
 * is plainly the way on and the rest reads as what to do on the way.
 */
@Composable
internal fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
) {
    Box(
        modifier
            .pressable({ if (!busy) onClick() }, pressedScale = 0.97f)
            .fillMaxWidth()
            .height(52.dp)
            .setupCard(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = Ink,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
                maxLines = 1,
            )
        }
    }
}

/** A card that goes somewhere: a title, a line about it, and where it points. */
@Composable
internal fun CardButton(
    title: String,
    onClick: () -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    Row(
        modifier
            .pressable(onClick, pressedScale = 0.98f)
            .fillMaxWidth()
            .setupCard()
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
            )
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkDim,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        Icon(
            icon,
            contentDescription = null,
            tint = InkDim,
            modifier = Modifier
                .padding(start = 12.dp)
                .size(18.dp),
        )
    }
}

/** The way back, at the top: a small round card with the arrow in it. */
@Composable
internal fun RoundButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        Modifier
            .pressable(onClick, pressedScale = 0.92f)
            .size(42.dp)
            .setupCard(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Ink, modifier = Modifier.size(19.dp))
    }
}

/** Words that can be tapped and are not the point: skipping, copying. */
@Composable
internal fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = InkDim,
        modifier = modifier
            .clip(CircleShape)
            .pressable(onClick, shape = CircleShape)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

/** Where a step is in the guide, over what it asks. */
@Composable
internal fun StepHeader(title: String, overline: String? = null) {
    Column(Modifier.padding(top = 18.dp)) {
        if (overline != null) {
            Text(
                overline.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = InkDim,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Text(title, style = MaterialTheme.typography.headlineLarge, color = Ink)
    }
}

@Composable
internal fun ProseText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = Prose,
        modifier = Modifier.padding(top = 12.dp),
    )
}

/**
 * Something that will cost the user time if skipped, rather than mere prose.
 *
 * Marked by the card and a small sign, not by a colour: the accent it used to
 * be tinted with was the record's, and said nothing about the warning.
 */
@Composable
internal fun Note(text: String) {
    Row(
        Modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .setupCard(RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Icon(
            PhosphorIcons.Regular.Info,
            contentDescription = null,
            tint = InkDim,
            modifier = Modifier
                .padding(top = 1.dp, end = 12.dp)
                .size(18.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Ink)
    }
}

/** A step already done, said with the ink turned over, as a lit chip is. */
@Composable
internal fun DoneRow(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Ink),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                PhosphorIcons.Regular.Check,
                contentDescription = null,
                tint = pageGround(),
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Ink,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

/** How far through the guide: the current step drawn long, the rest as dots. */
@Composable
internal fun StepDots(count: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { index ->
            val here = index == current
            val width by animateDpAsState(
                targetValue = if (here) 18.dp else 6.dp,
                animationSpec = tween(220),
                label = "stepDot",
            )
            Box(
                Modifier
                    .height(6.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(if (here) Ink else Ink.copy(alpha = 0.2f)),
            )
        }
    }
}

/** A numbered line of a list inside a card, with room under it for more. */
@Composable
internal fun ListStep(
    index: Int,
    text: String,
    extra: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .border(1.dp, Ink.copy(alpha = 0.22f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$index",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
            )
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp, top = 2.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Prose)
            extra?.invoke()
        }
    }
}

/** The line between two of those, starting where their text does. */
@Composable
internal fun ListDivider() {
    Box(
        Modifier
            .padding(start = 52.dp, end = 16.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(Ink.copy(alpha = 0.07f)),
    )
}

internal val PAGE_MARGIN = 24.dp
private val TOP_BAR = 60.dp
private val CardShape = RoundedCornerShape(20.dp)
