package dev.lelonio.square.ui.onboarding

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.bold.Check
import dev.lelonio.square.ui.glass.LiquidButton
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.emptyBackdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.layerBackdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import dev.lelonio.square.ui.glass.pressable
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import dev.lelonio.square.ui.theme.LocalInkOverride
import dev.lelonio.square.ui.theme.LocalLightTheme

/*
 * What the setup is drawn with: the first question, the guide and Soloist's
 * steps.
 *
 * The app's own material, the way its chrome is made. A dark ground with the
 * app's mark large on it and one slow light, and over it a single sheet of
 * the app's glass at the foot of the screen that every step is a page of:
 * the sheet keeps its place and changes height from one step to the next,
 * the way the player's panels open, rather than a new screen sliding in. The
 * buttons are the app's liquid buttons; what is listed inside the sheet is
 * grouped rows, as in the settings, not cards on cards.
 *
 * Always the dark side, in white ink: the light under the glass is what gives
 * it something to bend, and on a white page there would be none.
 */

/** What the setup's glass refracts: the ground under the sheet. Set by [SetupScaffold]. */
internal val LocalSetupBackdrop = compositionLocalOf<Backdrop> { emptyBackdrop() }

/**
 * The page every screen of the setup is laid on.
 *
 * [top] is the bar over the ground (back, skip). [content] is the step, inside
 * the sheet, scrolling when it is taller than the room it has. [primary] is
 * the way on, at the foot of the sheet, and [progress] (the step shown, of how
 * many) the dots under it.
 */
@Composable
internal fun SetupScaffold(
    top: @Composable RowScope.() -> Unit,
    primary: (@Composable () -> Unit)? = null,
    progress: Pair<Int, Int>? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val backdrop = rememberLayerBackdrop()
    LightStatusIcons()

    Box(
        Modifier
            .fillMaxSize()
            // Opaque: nothing behind the setup is meant to show or be reached
            // while it is up.
            .background(Ground)
            .clickable(interactionSource = null, indication = null) {},
    ) {
        SetupGround(Modifier.fillMaxSize().layerBackdrop(backdrop))
        CompositionLocalProvider(
            LocalSetupBackdrop provides backdrop,
            LocalInkOverride provides Color.White,
            LocalLightTheme provides false,
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
                // The step, from the top: its name where the eye starts, what
                // it asks for under it, scrolling when it is taller than the
                // room the button leaves, its last line fading out rather
                // than cut at a hard edge.
                val scroll = rememberScrollState()
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .fadingEdges(scroll)
                        .verticalScroll(scroll)
                        .padding(horizontal = PAGE_PAD)
                        .padding(top = 12.dp, bottom = 24.dp),
                    content = content,
                )
                Column(Modifier.padding(horizontal = PAGE_PAD).padding(bottom = 12.dp)) {
                    if (primary != null) primary()
                    if (progress != null) {
                        Spacer(Modifier.height(18.dp))
                        StepDots(progress.second, progress.first)
                    }
                }
            }
        }
    }
}

/** Fades the content out at an edge it scrolls past. */
private fun Modifier.fadingEdges(scroll: androidx.compose.foundation.ScrollState): Modifier =
    this
        .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val edge = 28.dp.toPx()
            if (scroll.value < scroll.maxValue) {
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Black,
                        1f to Color.Transparent,
                        startY = size.height - edge,
                        endY = size.height,
                    ),
                    topLeft = Offset(0f, size.height - edge),
                    size = androidx.compose.ui.geometry.Size(size.width, edge),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
            }
            if (scroll.value > 0) {
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = edge),
                    size = androidx.compose.ui.geometry.Size(size.width, edge),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
            }
        }

/**
 * White status bar icons while the setup is up: it is always dark, and the
 * app's own theme may have asked for dark icons on a light page.
 */
@Composable
private fun LightStatusIcons() {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view) {
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            ?: generateSequence(view.context) { (it as? android.content.ContextWrapper)?.baseContext }
                .filterIsInstance<android.app.Activity>().firstOrNull()?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val was = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose { if (was != null) controller.isAppearanceLightStatusBars = was }
    }
}

private val Ground = Color(0xFF07080D)
private val Deep = Color(0xFF06301A)
private val Glow = Color(0xFF39D97A)

/**
 * The ground: black where the words are, deepening to the green of the app's
 * mark at the foot, where the button is. Still: nothing on this page moves but what the
 * person touches. The colour low down is what the glass button has to bend.
 */
@Composable
private fun SetupGround(modifier: Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier.background(
            Brush.verticalGradient(
                0f to Ground,
                0.42f to Ground,
                0.72f to Deep,
                1f to Glow,
            ),
        ),
    )
}

/**
 * A group of rows: a pane of faint glass with a hairline rim, the rows printed
 * on it as in the settings.
 */
@Composable
internal fun Modifier.setupCard(shape: Shape = GroupShape, fill: Float = 0.06f): Modifier =
    this
        .clip(shape)
        .background(Color.White.copy(alpha = fill))
        .border(0.75.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.06f))), shape)

/**
 * The app's icon as the launcher shows it: the plate and the green wave, cut
 * to a rounded square. Built from the two layers of the adaptive icon, drawn
 * at the size that puts their visible middle (72 of 108) inside the shape.
 */
@Composable
internal fun AppIcon(size: androidx.compose.ui.unit.Dp = 84.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.27f))
            .border(0.75.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(size * 0.27f)),
        contentAlignment = Alignment.Center,
    ) {
        val layer = Modifier.requiredSize(size * 108f / 72f)
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(dev.lelonio.square.R.mipmap.ic_launcher_background),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = layer,
        )
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(dev.lelonio.square.R.mipmap.ic_launcher_foreground),
            contentDescription = null,
            modifier = layer,
        )
    }
}

/** Running text: a step down from the ink, and still meant to be read. */
internal val Prose: Color
    @Composable get() = Ink.copy(alpha = 0.72f)

/**
 * The way on: the app's liquid button, filled near white with a dark label,
 * the one prominent control on the sheet; dimmed while the step it closes is
 * not done.
 */
@Composable
internal fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    LiquidButton(
        onClick = onClick,
        backdrop = LocalSetupBackdrop.current,
        enabled = enabled,
        surfaceColor = Color.White.copy(alpha = if (enabled) 0.16f else 0.06f),
        contentHeight = 54.dp,
        contentPadding = 20.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.45f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * An action inside a step (logging in, linking a key, saving): a row of its
 * own, centred, in the group style; the one way on stays [PrimaryButton].
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
            .fillMaxWidth()
            .height(52.dp)
            .setupCard(RowShape)
            .pressable({ if (!busy) onClick() }, pressedScale = 0.98f),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(strokeWidth = 2.dp, color = Ink, modifier = Modifier.size(18.dp))
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

/** A row that goes somewhere: a title, a line about it, and where it points. */
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
            .fillMaxWidth()
            .setupCard(RowShape)
            .pressable(onClick, pressedScale = 0.98f)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Ink)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkDim,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Icon(icon, contentDescription = null, tint = InkDim, modifier = Modifier.padding(start = 12.dp).size(18.dp))
    }
}

/** The way back, over the ground: a round liquid button. */
@Composable
internal fun RoundButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    LiquidButton(
        onClick = onClick,
        backdrop = LocalSetupBackdrop.current,
        contentHeight = 44.dp,
        contentPadding = 0.dp,
        modifier = Modifier.size(44.dp),
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, tint = Ink, modifier = Modifier.size(20.dp))
        }
    }
}

/** Words that can be tapped and are not the point (skipping, closing, copying). */
@Composable
internal fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    LiquidButton(
        onClick = onClick,
        backdrop = LocalSetupBackdrop.current,
        contentHeight = 40.dp,
        contentPadding = 16.dp,
        // A clear pane, not the ground's blue: inside a step it sits over
        // other glass, and bending that came out a dark blot.
        surfaceColor = Color.White.copy(alpha = 0.1f),
        modifier = modifier,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Ink)
    }
}

/**
 * A step's head: its symbol in a disc, then its name large; [overline] is
 * where it sits in a run of steps.
 */
@Composable
internal fun StepHeader(title: String, overline: String? = null, icon: ImageVector? = null) {
    if (overline != null) {
        Text(
            overline.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = InkDim,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
    Text(
        title,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold,
        color = Ink,
    )
}

/** The step's one line. */
@Composable
internal fun ProseText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Prose,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** What is worth knowing and not worth a paragraph: small, under the rest. */
@Composable
internal fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = InkDim,
        modifier = Modifier.padding(top = 14.dp),
    )
}

/** The green the system says "done" in. */
private val DoneGreen = Color(0xFF30C85E)

/** A step done: a green tick and what is now done. */
@Composable
internal fun DoneRow(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(DoneGreen),
            contentAlignment = Alignment.Center,
        ) {
            Icon(PhosphorIcons.Bold.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
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

/** Where in the steps this is: a short line for the one shown, dots for the rest. */
@Composable
internal fun StepDots(count: Int, current: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val width by animateDpAsState(if (i == current) 18.dp else 6.dp, tween(260), label = "dot")
            Box(
                Modifier
                    .width(width)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (i == current) 0.9f else 0.25f)),
            )
        }
    }
}

/** One numbered row of a checklist group. */
@Composable
internal fun ListStep(
    index: Int,
    text: String,
    extra: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("$index", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Ink)
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp, top = 1.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Prose)
            extra?.invoke()
        }
    }
}

/** The hairline between rows of a group, inset from its edge. */
@Composable
internal fun ListDivider() {
    Box(
        Modifier
            .padding(start = 50.dp)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(Color.White.copy(alpha = 0.1f)),
    )
}

/** The sheet's own inner margin, for content laid out to its edges. */
internal val PAGE_MARGIN = 0.dp // the page pads its steps itself
private val PAGE_PAD = 24.dp
private val TOP_BAR = 60.dp
private val GroupShape = RoundedCornerShape(16.dp)
private val RowShape = RoundedCornerShape(16.dp)
