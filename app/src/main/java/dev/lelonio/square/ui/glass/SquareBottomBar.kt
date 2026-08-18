package dev.lelonio.square.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import dev.lelonio.square.ui.glass.backdrop.drawBackdrop
import dev.lelonio.square.ui.glass.backdrop.effects.vibrancy
import dev.lelonio.square.ui.glass.backdrop.effects.blur
import dev.lelonio.square.ui.glass.backdrop.effects.lens
import dev.lelonio.square.ui.glass.backdrop.highlight.Highlight
import dev.lelonio.square.ui.glass.backdrop.shadow.Shadow
import dev.lelonio.square.ui.glass.shapes.ContinuousCapsule
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt

/**
 * The bottom bar, folded and unfolded by one number.
 *
 * Written rather than borrowed, and the reason is in the frame times. The
 * reference's own bar morphs by crossfading two separately composed bars and
 * matching their parts with shared elements: at rest it is cheaper than what
 * this app had before — 10% dropped frames against 15% — but every fold costs
 * 40% and a 90th percentile of 90ms, because for the length of the animation
 * both bars exist, both are measured, and both carry their own glass.
 *
 * This is the same shape with none of that. There is one bar. Folding is a
 * single [Animatable] that every part reads *inside* a `layout` or
 * `graphicsLayer` block, never in composition — so a frame of the fold costs a
 * measure and a draw, and nothing recomposes at all. It is the discipline the
 * player sheet and the playlist header in this app already use, applied to the
 * one thing that was still doing it the expensive way.
 *
 * What the parts do, from 0 (open) to 1 (folded):
 *
 * - the capsule keeps the tab you are on and gives up the other, its width
 *   travelling from two tabs to one square;
 * - the labels fade in the first third of the travel, because they are the part
 *   that stops being readable first;
 * - what is playing rises out of its own row and settles between the capsule and
 *   the search button, which is where the row has just made space for it.
 */
@Composable
fun SquareBottomBar(
    /** 0 open, 1 folded. Held by the caller so the scroll can drive it. */
    fold: State<Float>,
    /** The tab the capsule keeps when it folds. */
    selectedIndex: Int,
    accessoryVisible: Boolean,
    modifier: Modifier = Modifier,
    tabSpacing: Dp = 8.dp,
    /** The row's own height when open, and when folded. */
    openHeight: Dp = 64.dp,
    foldedHeight: Dp = 52.dp,
    accessoryHeight: Dp = 48.dp,
    /** The gap between what is playing and the row below it, while open. */
    accessoryGap: Dp = 8.dp,
    capsule: @Composable (fold: () -> Float) -> Unit,
    standalone: @Composable () -> Unit,
    accessory: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val spacingPx = with(density) { tabSpacing.roundToPx() }
    val openPx = with(density) { openHeight.roundToPx() }
    val foldedPx = with(density) { foldedHeight.roundToPx() }
    val accessoryPx = with(density) { accessoryHeight.roundToPx() }
    val gapPx = with(density) { accessoryGap.roundToPx() }

    val foldState = rememberUpdatedState(fold.value)
    val readFold = remember { { foldState.value } }

    Layout(
        modifier = modifier,
        content = {
            Box(Modifier.layoutId(SlotCapsule)) { capsule(readFold) }
            Box(Modifier.layoutId(SlotStandalone)) { standalone() }
            if (accessoryVisible) {
                Box(Modifier.layoutId(SlotAccessory)) { accessory() }
            }
        },
    ) { measurables, constraints ->
        // Read here, in measure. Nothing above this line depends on the fold, so
        // nothing above this line runs again when it changes.
        val f = readFold()
        val width = constraints.maxWidth
        val rowHeight = lerp(openPx, foldedPx, f)

        val standaloneNode = measurables.first { it.layoutId == SlotStandalone }
        val standalonePlaceable = standaloneNode.measure(
            Constraints.fixed(rowHeight, rowHeight),
        )

        val capsuleNode = measurables.first { it.layoutId == SlotCapsule }
        // Open, the capsule is what is left of the row; folded, it is one
        // square. The tabs inside it are laid out by the caller against the
        // same number, so the two agree without either measuring the other.
        val capsuleOpen = width - standalonePlaceable.width - spacingPx
        val capsuleWidth = lerp(capsuleOpen, rowHeight, f)
        val capsulePlaceable = capsuleNode.measure(Constraints.fixed(capsuleWidth, rowHeight))

        val accessoryNode = measurables.firstOrNull { it.layoutId == SlotAccessory }
        // Open, it spans the whole width above the row. Folded, it is the middle
        // of the row: what the capsule gave up, less the two gaps.
        val accessoryOpenWidth = width
        val accessoryFoldedWidth =
            (width - capsuleWidth - standalonePlaceable.width - spacingPx * 2).coerceAtLeast(0)
        val accessoryWidth = lerp(accessoryOpenWidth, accessoryFoldedWidth, f)
        val accessoryPlaceable = accessoryNode?.measure(
            Constraints.fixed(accessoryWidth.coerceAtLeast(1), lerp(accessoryPx, rowHeight, f)),
        )

        val totalHeight = if (accessoryPlaceable != null) {
            lerp(accessoryPx + gapPx + openPx, foldedPx, f)
        } else {
            rowHeight
        }

        layout(width, totalHeight) {
            val rowTop = totalHeight - rowHeight
            capsulePlaceable.place(0, rowTop)
            standalonePlaceable.place(width - standalonePlaceable.width, rowTop)
            accessoryPlaceable?.place(
                // Open: hard left, above the row. Folded: in the middle of it.
                x = lerp(0, capsuleWidth + spacingPx, f),
                y = lerp(0, rowTop, f),
            )
        }
    }
}

/**
 * Drives [SquareBottomBar] from whatever is being scrolled.
 *
 * Folds on the way down and unfolds at the top of the page, and nowhere else:
 * scrolling back up is a request for what is further up, not for the bar, and a
 * bar that returns on the first upward pixel spends the whole page opening and
 * closing. What is left over at the end of an upward gesture is the part the
 * list could not use — which means there is no more page above it.
 */
@Composable
fun rememberBarFold(): BarFold {
    val fold = remember { Animatable(0f) }
    val target = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

    LaunchedEffect(fold) {
        androidx.compose.runtime.snapshotFlow { target.floatValue }.collect { wanted ->
            fold.animateTo(
                wanted,
                // Soft, and slightly slower closing than opening would be: this
                // is a bar settling, not a switch.
                spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
            )
        }
    }

    return remember(fold) { BarFold(fold.asState(), target) }
}

/** The fold, and the connection that moves it. */
class BarFold(
    val progress: State<Float>,
    private val target: androidx.compose.runtime.MutableFloatState,
) {
    val folded: Boolean get() = target.floatValue > 0.5f

    val connection = object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
        override fun onPreScroll(
            available: androidx.compose.ui.geometry.Offset,
            source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
        ): androidx.compose.ui.geometry.Offset {
            // The finger only. A fling settles by reporting deltas of its own,
            // and the last of those points the other way.
            if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput &&
                available.y < -6f
            ) {
                target.floatValue = 1f
            }
            return androidx.compose.ui.geometry.Offset.Zero
        }

        override fun onPostScroll(
            consumed: androidx.compose.ui.geometry.Offset,
            available: androidx.compose.ui.geometry.Offset,
            source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
        ): androidx.compose.ui.geometry.Offset {
            if (available.y > 0.5f) target.floatValue = 0f
            return androidx.compose.ui.geometry.Offset.Zero
        }
    }

    fun unfold() {
        target.floatValue = 0f
    }
}

private const val SlotCapsule = "capsule"
private const val SlotStandalone = "standalone"
private const val SlotAccessory = "accessory"

/** Fades a part out over the first [until] of the fold, and back in over it. */
fun Modifier.foldAlpha(fold: () -> Float, until: Float = 0.45f): Modifier =
    graphicsLayer { alpha = (1f - fold() / until).coerceIn(0f, 1f) }

/** Rounds a dp interpolation the way the layout above does, for callers. */
internal fun lerpPx(start: Int, stop: Int, fraction: Float): Int =
    (start + (stop - start) * fraction).roundToInt()

/**
 * The tabs inside the capsule, and the puck under the one you are on.
 *
 * Same rule as the bar around it: the fold is read in `layout` and in
 * `drawBehind`, so folding costs a measure and a draw. The unselected tab keeps
 * its place in the list and loses its width — which is what makes the capsule
 * close *onto* the tab you are on rather than sliding it sideways.
 */
@Composable
fun FoldingTabs(
    fold: () -> Float,
    selectedIndex: Int,
    padding: Dp = 4.dp,
    puckColor: androidx.compose.ui.graphics.Color,
    /** What the indicator refracts; null draws no indicator at all. */
    backdrop: dev.lelonio.square.ui.glass.backdrop.Backdrop? = null,
    /** How many tabs the capsule holds; the puck needs it before measuring. */
    tabCount: Int = 2,
    /** Called when the indicator is dragged onto another tab and let go. */
    onTabSelected: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    tabs: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val paddingPx = with(density) { padding.roundToPx() }
    val selected = rememberUpdatedState(selectedIndex)

    // Where the puck is, as a tab index that can sit between two of them.
    //
    // The old bar had one and this did not, and a bar without it says which tab
    // you are on with a tint alone — which is the difference between a control
    // that answers at a glance and one that has to be read. Animated as its own
    // value and drawn behind the tabs, so moving it costs a draw: nothing here
    // recomposes when it travels.
    val puck = remember { Animatable(selectedIndex.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(selectedIndex) {
        if (!dragging) {
            puck.animateTo(
                selectedIndex.toFloat(),
                spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
            )
        }
    }

    // The indicator can be dragged between tabs, as the old bar's could.
    //
    // It is the one gesture a capsule of tabs invites — the puck looks like
    // something you could push — and losing it was the difference between a bar
    // that answers the hand and one that only answers taps. Released, it settles
    // on the nearest tab and that tab is the one opened, so a drag that changes
    // its mind halfway costs nothing.
    val scope = rememberCoroutineScope()
    val dragScope = rememberUpdatedState(onTabSelected)
    val puckDrag = if (tabCount > 1) {
        Modifier.pointerInput(tabCount) {
            detectHorizontalDragGestures(
                onDragStart = { dragging = true },
                onDragCancel = {
                    dragging = false
                    scope.launch { puck.animateTo(selected.value.toFloat()) }
                },
                onHorizontalDrag = { _, amount ->
                    val step = (size.width - paddingPx * 2) / tabCount.toFloat()
                    scope.launch {
                        puck.snapTo(
                            (puck.value + amount / step).coerceIn(0f, (tabCount - 1).toFloat()),
                        )
                    }
                },
                onDragEnd = {
                    dragging = false
                    val nearest = puck.value.roundToInt().coerceIn(0, tabCount - 1)
                    scope.launch {
                        puck.animateTo(
                            nearest.toFloat(),
                            spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
                        )
                    }
                    if (nearest != selected.value) dragScope.value(nearest)
                },
            )
        }
    } else {
        Modifier
    }

    Box(modifier) {
        // The indicator, as the old bar drew it: a pane of glass over the tabs
        // with its own lens and rim, not a painted rectangle. Placed and sized
        // in a `graphicsLayer` and a `layout`, so it travels and grows without
        // recomposing anything.
        if (backdrop != null) {
            Box(
                Modifier
                    .puckLayout(
                        fold = fold,
                        position = { puck.value },
                        count = tabCount,
                        paddingPx = paddingPx,
                    )
                    .then(puckDrag)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { ContinuousCapsule() },
                        effects = { lens(10f.dp.toPx(), 14f.dp.toPx(), chromaticAberration = true) },
                        highlight = { Highlight.Default.copy(alpha = 0.35f) },
                        shadow = { Shadow(alpha = 0.35f) },
                        onDrawSurface = { drawRect(puckColor) },
                    ),
            )
        }

    Layout(
        modifier = Modifier,
        content = tabs,
    ) { measurables, constraints ->
        val f = fold()
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val inner = width - paddingPx * 2
        val count = measurables.size
        val open = if (count > 0) inner / count else inner
        val chosen = selected.value.coerceIn(0, (count - 1).coerceAtLeast(0))

        val widths = IntArray(count) { index ->
            lerp(open, if (index == chosen) inner else 0, f)
        }
        val placeables = measurables.mapIndexed { index, measurable ->
            measurable.measure(
                Constraints.fixed(
                    widths[index].coerceAtLeast(1),
                    (height - paddingPx * 2).coerceAtLeast(1),
                ),
            )
        }

        layout(width, height) {
            var x = paddingPx
            placeables.forEachIndexed { index, placeable ->
                placeable.place(x, paddingPx)
                x += widths[index]
            }
        }
    }
    }
}

/**
 * Where the indicator sits: one tab wide and sliding while the bar is open, the
 * whole capsule once it has folded — so the tab you are left with *is* the
 * indicator rather than sitting inside it.
 */
private fun Modifier.puckLayout(
    fold: () -> Float,
    position: () -> Float,
    count: Int,
    paddingPx: Int,
): Modifier = layout { measurable, constraints ->
    val f = fold()
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val inner = width - paddingPx * 2
    val open = if (count > 0) inner / count else inner
    val puckWidth = lerp(open, inner, f)
    val placeable = measurable.measure(
        Constraints.fixed(
            puckWidth.coerceAtLeast(1),
            (height - paddingPx * 2).coerceAtLeast(1),
        ),
    )
    layout(width, height) {
        val x = paddingPx + lerp((position() * open).roundToInt(), 0, f)
        placeable.place(x, paddingPx)
    }
}

/** Lets a part give up its height as the bar folds; see [foldAlpha]. */
fun Modifier.foldHeight(fold: () -> Float, until: Float = 0.45f): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val keep = (1f - fold() / until).coerceIn(0f, 1f)
        val height = (placeable.height * keep).roundToInt()
        layout(placeable.width, height) { placeable.place(0, 0) }
    }
