/*
 * FloatingTabBar by Elyes Mansour
 * https://github.com/elyesmansour/compose-floating-tab-bar
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Vendored from the repository's main branch rather than taken as a
 * dependency: the published AAR is compiled against a Compose version this app
 * does not use, and it crashes with NoSuchMethodError on
 * SharedTransitionScope.sharedElement. Compiling the source here keeps it
 * binary-compatible.
 *
 * Kept as close to upstream as it can be. The library animates the fold itself
 * — shared bounds on a spring, with the crossfade only clearing away what has
 * no counterpart in the other state — and every local attempt to describe that
 * movement by hand made it worse. What this app adds goes through the seams the
 * library already has: the glass through tabBarContentModifier, the selection
 * shape through colors.selectedTabBackgroundColor.
 */

@file:OptIn(ExperimentalSharedTransitionApi::class)

package dev.lelonio.square.ui.glass.floatingtabbar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp


/**
 * A floating tab bar that can transition between inline and expanded states.
 *
 * @param isInline controls the FloatingTabBar's inline state
 * @param selectedTabKey the key of the currently selected tab
 * @param modifier the modifier to be applied to the tab bar
 * @param colors the colors used by the tab bar components
 * @param shapes the shapes used by the tab bar components
 * @param sizes the sizes and spacing used by the tab bar components
 * @param elevations the elevation values used by the tab bar components
 * @param tabBarContentModifier modifier applied to the tab bar sections containing the grouped tabs and standalone tab.
 * It is applied after the default styling (background, shadow, clip) but before any content padding.
 * @param inlineAccessory the accessory composable that appears in inline state (e.g., compact media player)
 * @param expandedAccessory the accessory composable that appears in expanded state (e.g., full media player)
 * @param contentKey optional key that when changed retriggers the content lambda
 * @param content the content defining the tabs
 */
@Composable
fun FloatingTabBar(
    isInline: Boolean,
    selectedTabKey: Any?,
    modifier: Modifier = Modifier,
    tabBarContentModifier: Modifier = Modifier,
    inlineAccessory: (@Composable SharedTransitionScope.(Modifier, AnimatedVisibilityScope) -> Unit)? = null,
    expandedAccessory: (@Composable SharedTransitionScope.(Modifier, AnimatedVisibilityScope) -> Unit)? = null,
    colors: FloatingTabBarColors = FloatingTabBarDefaults.colors(),
    shapes: FloatingTabBarShapes = FloatingTabBarDefaults.shapes(),
    sizes: FloatingTabBarSizes = FloatingTabBarDefaults.sizes(),
    elevations: FloatingTabBarElevations = FloatingTabBarDefaults.elevations(),
    tabsFillWidth: Boolean = false,
    selectedTabSurface: (@Composable () -> Modifier)? = null,
    /**
     * LOCAL CHANGE: the open bar's tab group, drawn by the caller.
     *
     * The catalog this app's glass comes from ships its own bottom tabs — the
     * pill, the tabs and the indicator that can be dragged between them — and
     * that component is what this app wants in the open bar. It is handed the
     * modifier the group would have had, so it still takes its place in the row
     * beside the standalone tab, and it still travels as the shared "tabGroup"
     * element when the bar folds. Null draws the library's own tabs.
     */
    expandedTabs: (@Composable SharedTransitionScope.(Modifier, AnimatedVisibilityScope) -> Unit)? = null,
    /**
     * LOCAL CHANGE: how tall [expandedTabs] stands.
     *
     * Not a preference: the row measures its children's intrinsic height to size
     * the standalone tab's square, and a component built on SubcomposeLayout —
     * which the catalog's tabs are, they measure themselves against their own
     * width — cannot answer that question at all. A height stated here is
     * answered without asking it.
     */
    expandedTabsHeight: Dp = Dp.Unspecified,
    /**
     * LOCAL CHANGE: the search control, grown into the row.
     *
     * With this true and [searchBarContent] given, the open bar becomes the tab
     * you are on and a field taking the rest of the row: the tab group folds
     * away and the standalone circle is what the field grows out of — they share
     * bounds, so it is one shape stretching rather than a circle vanishing and a
     * pill appearing. The folded state is untouched.
     */
    /**
     * LOCAL CHANGE: whether the standalone tab keeps a circle of its own while
     * the bar is open.
     *
     * Upstream it always does — the places in a pill and search beside it. Here
     * search is one of the places, so the circle would be the same control
     * twice; it is kept for the folded bar, where the pill is gone and there is
     * nothing else to tap.
     */
    standaloneInExpanded: Boolean = true,
    searchMode: Boolean = false,
    searchBarContent: (@Composable (Modifier) -> Unit)? = null,
    contentKey: Any? = null,
    content: FloatingTabBarScope.() -> Unit
) {
    val scrollConnection = rememberFloatingTabBarScrollConnection(
        initialIsInline = isInline,
        inlineBehavior = FloatingTabBarInlineBehavior.Never
    )

    LaunchedEffect(isInline) {
        if (isInline) scrollConnection.inline() else scrollConnection.expand()
    }

    FloatingTabBar(
        selectedTabKey = selectedTabKey,
        scrollConnection = scrollConnection,
        modifier = modifier,
        tabBarContentModifier = tabBarContentModifier,
        inlineAccessory = inlineAccessory,
        expandedAccessory = expandedAccessory,
        colors = colors,
        shapes = shapes,
        sizes = sizes,
        elevations = elevations,
        tabsFillWidth = tabsFillWidth,
        selectedTabSurface = selectedTabSurface,
        expandedTabs = expandedTabs,
        expandedTabsHeight = expandedTabsHeight,
        standaloneInExpanded = standaloneInExpanded,
        searchMode = searchMode,
        searchBarContent = searchBarContent,
        contentKey = contentKey,
        content = content
    )
}

/**
 * A floating tab bar that transitions between inline and expanded states based on scroll behavior.
 *
 * @param selectedTabKey the key of the currently selected tab
 * @param scrollConnection the scroll connection that handles state transitions
 * @param modifier the modifier to be applied to the tab bar
 * @param colors the colors used by the tab bar components
 * @param shapes the shapes used by the tab bar components
 * @param sizes the sizes and spacing used by the tab bar components
 * @param elevations the elevation values used by the tab bar components
 * @param tabBarContentModifier modifier applied to the tab bar sections containing the grouped tabs and standalone tab.
 * It is applied after the default styling (background, shadow, clip) but before any content padding.
 * @param inlineAccessory the accessory composable that appears in inline state (e.g., compact media player)
 * @param expandedAccessory the accessory composable that appears in expanded state (e.g., full media player)
 * @param contentKey optional key that when changed retriggers the content lambda
 * @param content the content defining the tabs
 */
@Composable
fun FloatingTabBar(
    selectedTabKey: Any?,
    scrollConnection: FloatingTabBarScrollConnection,
    modifier: Modifier = Modifier,
    tabBarContentModifier: Modifier = Modifier,
    inlineAccessory: (@Composable SharedTransitionScope.(Modifier, AnimatedVisibilityScope) -> Unit)? = null,
    expandedAccessory: (@Composable SharedTransitionScope.(Modifier, AnimatedVisibilityScope) -> Unit)? = null,
    colors: FloatingTabBarColors = FloatingTabBarDefaults.colors(),
    shapes: FloatingTabBarShapes = FloatingTabBarDefaults.shapes(),
    sizes: FloatingTabBarSizes = FloatingTabBarDefaults.sizes(),
    elevations: FloatingTabBarElevations = FloatingTabBarDefaults.elevations(),
    /**
     * LOCAL CHANGE: whether the open tab group spreads across the row.
     *
     * Upstream sizes the group to its own content, which is right for the bar
     * it was written for — three long labels fill the row on their own. Four
     * short ones do not: the pill stopped two thirds of the way across and left
     * a hole between it and the search circle. With this true the group takes
     * the width the row has and its tabs share it equally, which is what the
     * reference looks like whatever its labels happen to say.
     */
    tabsFillWidth: Boolean = false,
    /**
     * LOCAL CHANGE: what the lit slot is made of.
     *
     * Upstream fills the selected tab with a colour, which is right for a bar
     * drawn in paint. This app's bar is glass, and a painted rectangle on it is
     * the one thing that reads as pasted on — so the caller hands over a
     * modifier instead, and it is applied to a shape that travels between the
     * tabs rather than to the tabs themselves. Null keeps upstream's colour.
     *
     * Whatever it returns must draw something of its own: a surface that only
     * samples the page behind the bar redraws it sharp inside the puck, which
     * reads as a hole rather than as a lit slot.
     */
    selectedTabSurface: (@Composable () -> Modifier)? = null,
    /**
     * LOCAL CHANGE: the open bar's tab group, drawn by the caller.
     *
     * The catalog this app's glass comes from ships its own bottom tabs — the
     * pill, the tabs and the indicator that can be dragged between them — and
     * that component is what this app wants in the open bar. It is handed the
     * modifier the group would have had, so it still takes its place in the row
     * beside the standalone tab, and it still travels as the shared "tabGroup"
     * element when the bar folds. Null draws the library's own tabs.
     */
    expandedTabs: (@Composable SharedTransitionScope.(Modifier, AnimatedVisibilityScope) -> Unit)? = null,
    /**
     * LOCAL CHANGE: how tall [expandedTabs] stands.
     *
     * Not a preference: the row measures its children's intrinsic height to size
     * the standalone tab's square, and a component built on SubcomposeLayout —
     * which the catalog's tabs are, they measure themselves against their own
     * width — cannot answer that question at all. A height stated here is
     * answered without asking it.
     */
    expandedTabsHeight: Dp = Dp.Unspecified,
    /**
     * LOCAL CHANGE: the search control, grown into the row.
     *
     * With this true and [searchBarContent] given, the open bar becomes the tab
     * you are on and a field taking the rest of the row: the tab group folds
     * away and the standalone circle is what the field grows out of — they share
     * bounds, so it is one shape stretching rather than a circle vanishing and a
     * pill appearing. The folded state is untouched.
     */
    /**
     * LOCAL CHANGE: whether the standalone tab keeps a circle of its own while
     * the bar is open.
     *
     * Upstream it always does — the places in a pill and search beside it. Here
     * search is one of the places, so the circle would be the same control
     * twice; it is kept for the folded bar, where the pill is gone and there is
     * nothing else to tap.
     */
    standaloneInExpanded: Boolean = true,
    searchMode: Boolean = false,
    searchBarContent: (@Composable (Modifier) -> Unit)? = null,
    contentKey: Any? = null,
    content: FloatingTabBarScope.() -> Unit
) {
    val scope = remember(contentKey) { FloatingTabBarScopeImpl().apply { content() } }

    val isAccessoryShared = inlineAccessory != null && expandedAccessory != null

    // Three shapes rather than two; see searchMode.
    val visual = when {
        scrollConnection.isInline -> FloatingTabBarVisual.INLINE
        searchMode && searchBarContent != null -> FloatingTabBarVisual.SEARCH
        else -> FloatingTabBarVisual.EXPANDED
    }

    SharedTransitionLayout(modifier = modifier) {
        // LOCAL CHANGE: the fold as two drops of water rather than two layouts.
        //
        // Held as a transition of its own so the merge can be timed against it:
        // the shapes travel through the middle of the fold, and it is only there
        // that the blur-and-threshold pass has anything to join. The radius runs
        // 0 at both ends and peaks halfway, which is what draws the neck between
        // the circles, thins it, and pinches it off. See gooey.
        val fold = updateTransition(targetState = visual, label = "fold")
        val gooeyRadius by fold.animateFloat(
            transitionSpec = {
                keyframes {
                    durationMillis = GooeyDurationMs
                    0f at 0
                    GooeyPeakBlur at GooeyDurationMs / 2 using FastOutSlowInEasing
                    0f at GooeyDurationMs
                }
            },
            label = "gooey",
        ) { 0f }

        Box(Modifier.gooey { gooeyRadius }) {
        fold.AnimatedContent(
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            contentAlignment = Alignment.BottomStart
        ) { target ->
            if (target == FloatingTabBarVisual.SEARCH) {
                SearchExpandedBar(
                    scope = scope,
                    selectedTabKey = selectedTabKey,
                    accessory = expandedAccessory,
                    isAccessoryShared = isAccessoryShared,
                    colors = colors,
                    shapes = shapes,
                    sizes = sizes,
                    elevations = elevations,
                    tabBarContentModifier = tabBarContentModifier,
                    animatedVisibilityScope = this@AnimatedContent,
                    searchBarContent = searchBarContent ?: {},
                    rowHeight = expandedTabsHeight,
                )
            } else if (target == FloatingTabBarVisual.INLINE) {
                InlineBar(
                    scope = scope,
                    selectedTabKey = selectedTabKey,
                    accessory = inlineAccessory,
                    isAccessoryShared = isAccessoryShared,
                    onInlineTabClick = { scrollConnection.expand() },
                    colors = colors,
                    shapes = shapes,
                    sizes = sizes,
                    elevations = elevations,
                    tabBarContentModifier = tabBarContentModifier,
                    animatedVisibilityScope = this@AnimatedContent
                )
            } else {
                ExpandedBar(
                    scope = scope,
                    tabsFillWidth = tabsFillWidth,
                    selectedTabSurface = selectedTabSurface,
                    expandedTabs = expandedTabs,
                    expandedTabsHeight = expandedTabsHeight,
                    standaloneInExpanded = standaloneInExpanded,
                    selectedTabKey = selectedTabKey,
                    accessory = expandedAccessory,
                    isAccessoryShared = isAccessoryShared,
                    colors = colors,
                    shapes = shapes,
                    sizes = sizes,
                    elevations = elevations,
                    tabBarContentModifier = tabBarContentModifier,
                    animatedVisibilityScope = this@AnimatedContent
                )
            }
        }
        }
    }
}

/**
 * The merge, and how long it lasts.
 *
 * Blur enough for the two circles' edges to reach each other and no more: at a
 * larger radius the icons inside them smear, and the pass covers the whole bar.
 * The window is the fold's own — the shapes are between places for about that
 * long — and the radius is zero at both ends, so at rest this costs nothing: no
 * offscreen layer, no filter. See gooey.
 */
private const val GooeyPeakBlur = 14f
private const val GooeyDurationMs = 260

/** LOCAL CHANGE: the three shapes the bar can take; see searchMode. */
private enum class FloatingTabBarVisual { INLINE, EXPANDED, SEARCH }

/**
 * LOCAL CHANGE: the open bar with the field in it.
 *
 * The tab you are on stays as a round button at the start of the row — it is
 * how you get back — and the search control fills the rest. That control shares
 * its bounds with the standalone circle of the other two states, so entering
 * search is the circle stretching into a field rather than two shapes swapping.
 */
@Composable
private fun SharedTransitionScope.SearchExpandedBar(
    scope: FloatingTabBarScopeImpl,
    selectedTabKey: Any?,
    accessory: (@Composable SharedTransitionScope.(Modifier, AnimatedVisibilityScope) -> Unit)?,
    isAccessoryShared: Boolean,
    colors: FloatingTabBarColors,
    shapes: FloatingTabBarShapes,
    sizes: FloatingTabBarSizes,
    elevations: FloatingTabBarElevations,
    tabBarContentModifier: Modifier,
    animatedVisibilityScope: AnimatedVisibilityScope,
    searchBarContent: @Composable (Modifier) -> Unit,
    /** As tall as the open bar, so entering search does not change its height. */
    rowHeight: Dp,
) {
    val inlineTab = scope.getInlineTab(selectedTabKey)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(sizes.componentSpacing),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (accessory != null) {
            ExpandedAccessory(
                accessory = accessory,
                isAccessoryShared = isAccessoryShared,
                shapes = shapes,
                colors = colors,
                elevations = elevations,
                animatedVisibilityScope = animatedVisibilityScope,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(sizes.componentSpacing),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (rowHeight != Dp.Unspecified) Modifier.height(rowHeight) else Modifier
                )
        ) {
            if (inlineTab != null) {
                InlineTab(
                    inlineTab = inlineTab,
                    // Already open: a tap here navigates, and does not have to
                    // unfold anything.
                    onClick = inlineTab.onClick,
                    shapes = shapes,
                    sizes = sizes,
                    colors = colors,
                    elevations = elevations,
                    animatedVisibilityScope = animatedVisibilityScope,
                    tabBarContentModifier = tabBarContentModifier,
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f, matchHeightConstraintsFirst = true)
                )
            }

            searchBarContent(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .sharedElement(
                        sharedContentState = rememberSharedContentState("standaloneTab"),
                        animatedVisibilityScope = animatedVisibilityScope,
                        zIndexInOverlay = 1f
                    )
                    .shadow(shape = shapes.tabBarShape, elevation = elevations.expandedElevation)
                    .background(color = colors.backgroundColor, shape = shapes.tabBarShape)
                    .clip(shapes.tabBarShape)
                    .then(tabBarContentModifier)
            )
        }
    }
}

/**
 * A [NestedScrollConnection] that handles scroll events to transition between inline and expanded states.
 *
 * @param initialIsInline Initial state of the tab bar (inline or expanded).
 * @param scrollThresholdPx The minimum scroll distance in pixels required to trigger a state change.
 * @param inlineBehavior Defines when the tab bar should transition to inline state.
 */
class FloatingTabBarScrollConnection(
    initialIsInline: Boolean = false,
    private val scrollThresholdPx: Float,
    private val inlineBehavior: FloatingTabBarInlineBehavior = FloatingTabBarInlineBehavior.OnScrollDown
) : NestedScrollConnection {
    var isInline by mutableStateOf(initialIsInline)
        private set

    private var accumulatedScroll = 0f

    fun expand() {
        isInline = false
        accumulatedScroll = 0f
    }

    fun inline() {
        isInline = true
        accumulatedScroll = 0f
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        // If behavior is Never, don't change state
        if (inlineBehavior == FloatingTabBarInlineBehavior.Never) {
            return Offset.Zero
        }

        val scrollDelta = available.y

        // Reset accumulated scroll if changing direction
        if ((accumulatedScroll > 0 && scrollDelta < 0) || (accumulatedScroll < 0 && scrollDelta > 0)) {
            accumulatedScroll = 0f
        }

        // Accumulate scroll
        accumulatedScroll += scrollDelta

        when (inlineBehavior) {
            FloatingTabBarInlineBehavior.OnScrollDown -> {
                // Check if we've scrolled enough to trigger state change
                if (accumulatedScroll <= -scrollThresholdPx && !isInline) {
                    // Scrolling down enough - transition to inline mode
                    isInline = true
                    accumulatedScroll = 0f // Reset after state change
                } else if (accumulatedScroll >= scrollThresholdPx && isInline) {
                    // Scrolling up enough - transition to expanded mode
                    isInline = false
                    accumulatedScroll = 0f // Reset after state change
                }
            }

            FloatingTabBarInlineBehavior.OnScrollUp -> {
                // Check if we've scrolled enough to trigger state change
                if (accumulatedScroll >= scrollThresholdPx && !isInline) {
                    // Scrolling up enough - transition to inline mode
                    isInline = true
                    accumulatedScroll = 0f // Reset after state change
                } else if (accumulatedScroll <= -scrollThresholdPx && isInline) {
                    // Scrolling down enough - transition to expanded mode
                    isInline = false
                    accumulatedScroll = 0f // Reset after state change
                }
            }

            FloatingTabBarInlineBehavior.Never -> {
                // Already handled above, but included for completeness
            }
        }

        return Offset.Zero // Don't consume the scroll, let it pass through
    }
}

/**
 * Creates and remembers a [FloatingTabBarScrollConnection] instance.
 *
 * @param initialIsInline Initial state of the tab bar (inline or expanded). Default is false.
 * @param scrollThreshold The minimum scroll distance required to trigger a state change. Default is 50.dp.
 * @param inlineBehavior Defines when the tab bar should transition to inline state. Default is [FloatingTabBarInlineBehavior.OnScrollDown].
 * @return A remembered [FloatingTabBarScrollConnection] instance.
 */
@Composable
fun rememberFloatingTabBarScrollConnection(
    initialIsInline: Boolean = false,
    scrollThreshold: Dp = 50.dp,
    inlineBehavior: FloatingTabBarInlineBehavior = FloatingTabBarInlineBehavior.OnScrollDown
): FloatingTabBarScrollConnection = with(LocalDensity.current) {
    val scrollThresholdPx = scrollThreshold.toPx()
    remember(scrollThresholdPx, inlineBehavior, initialIsInline) {
        FloatingTabBarScrollConnection(initialIsInline, scrollThresholdPx, inlineBehavior)
    }
}

/**
 * Defines when the floating tab bar should transition to inline state.
 */
enum class FloatingTabBarInlineBehavior {
    /** Never transition to inline - it stays in expanded state */
    Never,

    /** Transition to inline when scrolling down */
    OnScrollDown,

    /** Transition to inline when scrolling up */
    OnScrollUp
}

interface FloatingTabBarScope {
    /**
     * Adds a regular tab to the floating tab bar.
     *
     * @param key Unique identifier for the tab
     * @param title Composable content for the tab title
     * @param icon Composable content for the tab icon
     * @param onClick Callback invoked when the tab is clicked
     * @param indication Optional indication provider for touch feedback, defaults to LocalIndication.current
     */
    fun tab(
        key: Any,
        title: @Composable () -> Unit,
        icon: @Composable () -> Unit,
        onClick: () -> Unit,
        indication: (@Composable () -> Indication)? = { LocalIndication.current }
    )

    /**
     * Adds a standalone tab to the floating tab bar.
     *
     * Note: Calling this method more than once will override the previous standalone tab value.
     *
     * @param key Unique identifier for the standalone tab
     * @param icon Composable content for the tab icon
     * @param onClick Callback invoked when the tab is clicked
     * @param indication Optional indication provider for touch feedback, defaults to LocalIndication.current
     */
    fun standaloneTab(
        key: Any,
        icon: @Composable () -> Unit,
        onClick: () -> Unit,
        indication: (@Composable () -> Indication)? = { LocalIndication.current }
    )
}

@Composable
private fun SharedTransitionScope.InlineBar(
    scope: FloatingTabBarScopeImpl,
    selectedTabKey: Any?,
    accessory: (@Composable SharedTransitionScope.(Modifier, AnimatedVisibilityScope) -> Unit)?,
    isAccessoryShared: Boolean,
    onInlineTabClick: () -> Unit,
    colors: FloatingTabBarColors,
    shapes: FloatingTabBarShapes,
    sizes: FloatingTabBarSizes,
    elevations: FloatingTabBarElevations,
    tabBarContentModifier: Modifier,
    animatedVisibilityScope: AnimatedVisibilityScope
) {
    val inlineTab = scope.getInlineTab(selectedTabKey)
    val standaloneTab = scope.standaloneTab
    val hasInlineTab = inlineTab != null
    val hasStandaloneTab = standaloneTab != null

    // IntrinsicSize.Min makes the Row only as tall as its tallest child's intrinsic height,
    // which lets the standalone tab (fillMaxHeight + aspectRatio 1:1) become a square sized to
    // the tab group's height, and lets the accessory fill that same height via fillMaxHeight.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(sizes.componentSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (hasInlineTab) {
            InlineTab(
                inlineTab = inlineTab,
                onClick = onInlineTabClick,
                shapes = shapes,
                sizes = sizes,
                colors = colors,
                elevations = elevations,
                animatedVisibilityScope = animatedVisibilityScope,
                tabBarContentModifier = tabBarContentModifier,
                modifier = Modifier
            )
        }

        if (accessory != null) {
            InlineAccessory(
                accessory = accessory,
                isAccessoryShared = isAccessoryShared,
                shapes = shapes,
                colors = colors,
                elevations = elevations,
                animatedVisibilityScope = animatedVisibilityScope,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }

        if (hasStandaloneTab) {
            // The accessory's weight already consumes the slack and pushes the standalone tab to
            // the end. Without an accessory, a flexible spacer pins it to the end.
            if (accessory == null) {
                Spacer(Modifier.weight(1f))
            }

            InlineStandaloneTab(
                standaloneTab = standaloneTab,
                shapes = shapes,
                colors = colors,
                elevations = elevations,
                animatedVisibilityScope = animatedVisibilityScope,
                tabBarContentModifier = tabBarContentModifier,
                // When there is no tab group to match height with, the square would collapse to the
                // bare icon size. Apply the inline tab content padding so it stays a reasonable size.
                contentPadding = if (hasInlineTab) null else sizes.tabInlineContentPadding,
                modifier = if (hasInlineTab) {
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f)
                } else {
                    Modifier.aspectRatio(1f)
                }
            )
        }
    }
}

@Composable
private fun SharedTransitionScope.InlineTab(
    inlineTab: FloatingTabBarTab,
    // LOCAL CHANGE: what a tap does, chosen by the caller. Upstream unfolds the
    // bar and then runs the tab's own click as well, and the tab's click is a
    // navigation: on a page opened from a tab, a tap that was meant to bring
    // the bar back took you off the page. Folded, it unfolds and nothing else;
    // beside the search field, where there is nothing to unfold, it navigates.
    onClick: () -> Unit,
    shapes: FloatingTabBarShapes,
    sizes: FloatingTabBarSizes,
    colors: FloatingTabBarColors,
    elevations: FloatingTabBarElevations,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier,
    tabBarContentModifier: Modifier
) {
    Box(
        // LOCAL CHANGE: centred. Upstream's default is top-start, which nobody
        // notices while this box is sized to its own content — it shows the
        // moment a caller makes it square, with the glyph sitting up in the
        // corner of its circle.
        contentAlignment = Alignment.Center,
        modifier = modifier
            .sharedElement(
                sharedContentState = rememberSharedContentState("tabGroup"),
                animatedVisibilityScope = animatedVisibilityScope,
                zIndexInOverlay = 1f
            )
            .shadow(
                shape = shapes.tabBarShape,
                elevation = elevations.inlineElevation
            )
            .background(
                color = colors.backgroundColor,
                shape = shapes.tabBarShape
            )
            .clip(shapes.tabBarShape)
            .then(tabBarContentModifier)
            .clickable(
                onClick = onClick,
                indication = inlineTab.indication?.invoke(),
                interactionSource = remember { MutableInteractionSource() }
            )
            .padding(sizes.tabInlineContentPadding)
    ) {
        Tab(
            icon = {
                Box(
                    Modifier.sharedElement(
                        sharedContentState = rememberSharedContentState("tab#${inlineTab.key}-icon"),
                        animatedVisibilityScope = animatedVisibilityScope,
                        zIndexInOverlay = 1f
                    )
                ) {
                    inlineTab.icon()
                }
            },
            title = { inlineTab.title() },
            isInline = true
        )
    }
}

@Composable
private fun SharedTransitionScope.InlineStandaloneTab(
    standaloneTab: FloatingTabBarTab,
    shapes: FloatingTabBarShapes,
    colors: FloatingTabBarColors,
    elevations: FloatingTabBarElevations,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier,
    tabBarContentModifier: Modifier,
    contentPadding: PaddingValues?
) {
    Tab(
        icon = standaloneTab.icon,
        title = standaloneTab.title,
        isInline = true,
        isStandalone = true,
        modifier = modifier
            .sharedElement(
                sharedContentState = rememberSharedContentState("standaloneTab"),
                animatedVisibilityScope = animatedVisibilityScope,
                zIndexInOverlay = 1f
            )
            .shadow(
                shape = shapes.standaloneTabShape,
                elevation = elevations.inlineElevation
            )
            .background(
                color = colors.backgroundColor,
                shape = shapes.standaloneTabShape
            )
            .clip(shapes.standaloneTabShape)
            .then(tabBarContentModifier)
            .clickable(
                onClick = standaloneTab.onClick,
                indication = standaloneTab.indication?.invoke(),
                interactionSource = remember { MutableInteractionSource() }
            )
            .then(if (contentPadding != null) Modifier.padding(contentPadding) else Modifier)
    )
}

@Composable
private fun SharedTransitionScope.InlineAccessory(
    accessory: (@Composable SharedTransitionScope.(Modifier, AnimatedVisibilityScope) -> Unit)?,
    isAccessoryShared: Boolean,
    colors: FloatingTabBarColors,
    shapes: FloatingTabBarShapes,
    elevations: FloatingTabBarElevations,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier
) {
    accessory?.let { accessory ->
        Box(
            modifier = modifier
                .then(
                    if (isAccessoryShared) {
                        Modifier.sharedElement(
                            sharedContentState = rememberSharedContentState("accessory"),
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                    } else {
                        Modifier.animateEnterExitAccessory(
                            sharedTransitionScope = this,
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                    }
                )
        ) {
            accessory(
                Modifier
                    .fillMaxSize()
                    .shadow(
                        shape = shapes.accessoryShape,
                        elevation = elevations.inlineElevation
                    )
                    .background(color = colors.accessoryBackgroundColor, shapes.accessoryShape)
                    .clip(shapes.accessoryShape),
                animatedVisibilityScope
            )
        }
    }
}

@Composable
private fun SharedTransitionScope.ExpandedBar(
    scope: FloatingTabBarScopeImpl,
    tabsFillWidth: Boolean = false,
    standaloneInExpanded: Boolean = true,
    selectedTabSurface: (@Composable () -> Modifier)? = null,
    expandedTabs: (@Composable SharedTransitionScope.(Modifier, AnimatedVisibilityScope) -> Unit)? = null,
    /**
     * LOCAL CHANGE: how tall [expandedTabs] stands.
     *
     * Not a preference: the row measures its children's intrinsic height to size
     * the standalone tab's square, and a component built on SubcomposeLayout —
     * which the catalog's tabs are, they measure themselves against their own
     * width — cannot answer that question at all. A height stated here is
     * answered without asking it.
     */
    expandedTabsHeight: Dp = Dp.Unspecified,
    selectedTabKey: Any?,
    accessory: (@Composable SharedTransitionScope.(Modifier, AnimatedVisibilityScope) -> Unit)?,
    isAccessoryShared: Boolean,
    colors: FloatingTabBarColors,
    shapes: FloatingTabBarShapes,
    sizes: FloatingTabBarSizes,
    elevations: FloatingTabBarElevations,
    tabBarContentModifier: Modifier,
    animatedVisibilityScope: AnimatedVisibilityScope
) {
    val standaloneTab = scope.standaloneTab
    val hasStandaloneTab = standaloneTab != null && standaloneInExpanded
    val hasTabGroup = scope.tabs.isNotEmpty()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(sizes.componentSpacing)
    ) {
        if (accessory != null) {
            ExpandedAccessory(
                accessory = accessory,
                isAccessoryShared = isAccessoryShared,
                shapes = shapes,
                colors = colors,
                elevations = elevations,
                animatedVisibilityScope = animatedVisibilityScope,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // IntrinsicSize.Min makes the Row only as tall as its tallest child's intrinsic height,
        // letting the standalone tab (fillMaxHeight + aspectRatio 1:1) become a square sized to
        // the tab group's height. The tab group takes weight(1f) but wraps its own content, so it
        // only occupies what it needs while the standalone tab stays pinned to the end.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            // With a tab group present, its weight(1f) pushes the standalone tab to the end and
            // spacedBy provides the gap. Without one, End alignment pins the lone standalone tab
            // to the end.
            horizontalArrangement = if (hasTabGroup) {
                Arrangement.spacedBy(sizes.componentSpacing)
            } else {
                Arrangement.End
            },
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasTabGroup && expandedTabs != null) {
                // The caller's own tab group, in the place the library's would
                // have had and travelling as the same shared element; see the
                // parameter.
                Box(
                    Modifier
                        .weight(1f)
                        .then(
                            if (expandedTabsHeight != Dp.Unspecified) {
                                Modifier.height(expandedTabsHeight)
                            } else {
                                Modifier
                            }
                        )
                        .sharedElement(
                            sharedContentState = rememberSharedContentState("tabGroup"),
                            animatedVisibilityScope = animatedVisibilityScope,
                            zIndexInOverlay = 1f
                        )
                ) {
                    // The scopes go with it: the row is drawn by the caller,
                    // and the parts of it that survive the fold — the search
                    // slot, which becomes the circle — have to be able to say
                    // so. Same shape the accessory is handed.
                    expandedTabs(Modifier.fillMaxWidth(), animatedVisibilityScope)
                }
            } else if (hasTabGroup) {
                ExpandedTabs(
                    scope = scope,
                    tabsFillWidth = tabsFillWidth,
                    selectedTabSurface = selectedTabSurface,
                    selectedTabKey = selectedTabKey,
                    shapes = shapes,
                    sizes = sizes,
                    colors = colors,
                    elevations = elevations,
                    animatedVisibilityScope = animatedVisibilityScope,
                    tabBarContentModifier = tabBarContentModifier,
                    modifier = Modifier
                        .weight(1f)
                        .wrapContentWidth(align = Alignment.Start)
                )
            }

            if (hasStandaloneTab) {
                ExpandedStandaloneTab(
                    selectedTabKey = selectedTabKey,
                    standaloneTab = standaloneTab,
                    shapes = shapes,
                    colors = colors,
                    elevations = elevations,
                    animatedVisibilityScope = animatedVisibilityScope,
                    tabBarContentModifier = tabBarContentModifier,
                    // When there is no tab group to match height with, the square would collapse to
                    // the bare icon size. Apply the expanded tab content padding so it stays sized
                    // like a tab would be.
                    contentPadding = if (hasTabGroup) null else sizes.tabExpandedContentPadding,
                    modifier = if (hasTabGroup) {
                        Modifier
                            .fillMaxHeight()
                            .aspectRatio(1f)
                    } else {
                        Modifier.aspectRatio(1f)
                    }
                )
            }
        }
    }
}

@Composable
private fun SharedTransitionScope.ExpandedAccessory(
    accessory: @Composable SharedTransitionScope.(Modifier, AnimatedVisibilityScope) -> Unit,
    isAccessoryShared: Boolean,
    colors: FloatingTabBarColors,
    shapes: FloatingTabBarShapes,
    elevations: FloatingTabBarElevations,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier
) {
    Box(
        modifier = modifier
            .then(
                if (isAccessoryShared) {
                    Modifier.sharedElement(
                        sharedContentState = rememberSharedContentState("accessory"),
                        animatedVisibilityScope = animatedVisibilityScope
                    )
                } else {
                    Modifier.animateEnterExitAccessory(
                        sharedTransitionScope = this,
                        animatedVisibilityScope = animatedVisibilityScope
                    )
                }
            )
    ) {
        accessory(
            Modifier
                .shadow(
                    shape = shapes.accessoryShape,
                    elevation = elevations.expandedElevation
                )
                .background(color = colors.accessoryBackgroundColor, shapes.accessoryShape)
                .clip(shapes.accessoryShape),
            animatedVisibilityScope
        )
    }
}
@Composable
private fun SharedTransitionScope.ExpandedTabs(
    scope: FloatingTabBarScopeImpl,
    tabsFillWidth: Boolean = false,
    selectedTabSurface: (@Composable () -> Modifier)? = null,
    selectedTabKey: Any?,
    shapes: FloatingTabBarShapes,
    sizes: FloatingTabBarSizes,
    colors: FloatingTabBarColors,
    elevations: FloatingTabBarElevations,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier,
    tabBarContentModifier: Modifier
) {
    val inlineTab = scope.getInlineTab(selectedTabKey)

    // LOCAL CHANGE: the lit slot as a shape that travels.
    //
    // Upstream fades a colour in behind whichever tab is selected, so the mark
    // appears in its new place rather than moving there. With the tabs sharing
    // the row equally, where the mark belongs is arithmetic — the slot index
    // times the slot width — so it can simply be drawn once and moved, and a
    // spring is what carries it. See selectedTabSurface.
    val puckIndex = scope.tabs.indexOfFirst { it.key == selectedTabKey }
    val travellingPuck = selectedTabSurface != null && tabsFillWidth && puckIndex >= 0
    val puckOffset by animateFloatAsState(
        targetValue = puckIndex.coerceAtLeast(0).toFloat(),
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
        label = "puckOffset"
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(sizes.tabSpacing),
        modifier = modifier
            .sharedElement(
                sharedContentState = rememberSharedContentState("tabGroup"),
                animatedVisibilityScope = animatedVisibilityScope,
                zIndexInOverlay = 1f
            )
            .shadow(
                shape = shapes.tabBarShape,
                elevation = elevations.expandedElevation
            )
            .background(
                color = colors.backgroundColor,
                shape = shapes.tabBarShape
            )
            .clip(shapes.tabBarShape)
            .then(tabBarContentModifier)
            .padding(sizes.tabBarContentPadding)
            .then(
                if (tabsFillWidth) {
                    Modifier
                } else {
                    Modifier.wrapContentWidth(align = Alignment.Start, unbounded = true)
                }
            )
            .animateContentSize()
    ) {
        if (travellingPuck) {
            val slots = scope.tabs.size.coerceAtLeast(1)
            Box(
                Modifier
                    // Laid out as one slot of the row, then moved by whole slots:
                    // it occupies the same width a tab does, so the shape is the
                    // tab's own rather than something sized by eye.
                    .fillMaxWidth(1f / slots)
                    .fillMaxHeight()
                    .graphicsLayer {
                        translationX = puckOffset * size.width
                    }
                    .then(selectedTabSurface!!())
            )
        }
        scope.tabs.forEach { tab ->
            val isSelected = tab.key == selectedTabKey
            val animatedBgColor by animateColorAsState(
                targetValue = if (isSelected && !travellingPuck) {
                    colors.selectedTabBackgroundColor
                } else {
                    Color.Transparent
                },
                label = "tab_selected_bg"
            )
            Tab(
                icon = {
                    Box(
                        modifier = if (tab.key == inlineTab?.key) {
                            Modifier.sharedElement(
                                sharedContentState = rememberSharedContentState("tab#${tab.key}-icon"),
                                animatedVisibilityScope = animatedVisibilityScope,
                                zIndexInOverlay = 1f
                            )
                        } else {
                            Modifier.animateEnterExitTab(
                                sharedTransitionScope = this@ExpandedTabs,
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        }
                    ) {
                        tab.icon()
                    }
                },
                title = {
                    Box(
                        Modifier.animateEnterExitTab(
                            sharedTransitionScope = this@ExpandedTabs,
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                    ) {
                        tab.title()
                    }
                },
                isInline = false,
                modifier = Modifier
                    .then(if (tabsFillWidth) Modifier.weight(1f) else Modifier)
                    .skipToLookaheadSize()
                    .clip(shapes.tabShape)
                    .background(
                        color = animatedBgColor,
                        shape = shapes.tabShape
                    )
                    .clickable(
                        onClick = tab.onClick,
                        indication = tab.indication?.invoke(),
                        interactionSource = remember { MutableInteractionSource() }
                    )
                    .padding(sizes.tabExpandedContentPadding)
            )
        }
    }
}

@Composable
private fun SharedTransitionScope.ExpandedStandaloneTab(
    selectedTabKey: Any?,
    standaloneTab: FloatingTabBarTab,
    shapes: FloatingTabBarShapes,
    colors: FloatingTabBarColors,
    elevations: FloatingTabBarElevations,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier,
    tabBarContentModifier: Modifier,
    contentPadding: PaddingValues?
) {
    val isSelected = standaloneTab.key == selectedTabKey
    val animatedBgColor by animateColorAsState(
        targetValue = if (isSelected) colors.selectedStandaloneTabBackgroundColor else colors.backgroundColor,
        label = "standalone_tab_selected_bg"
    )
    Tab(
        icon = standaloneTab.icon,
        title = standaloneTab.title,
        isInline = false,
        isStandalone = true,
        modifier = modifier
            .sharedElement(
                sharedContentState = rememberSharedContentState("standaloneTab"),
                animatedVisibilityScope = animatedVisibilityScope,
                zIndexInOverlay = 1f
            )
            .shadow(
                shape = shapes.standaloneTabShape,
                elevation = elevations.expandedElevation
            )
            .background(
                color = animatedBgColor,
                shape = shapes.standaloneTabShape
            )
            .clip(shapes.standaloneTabShape)
            .then(tabBarContentModifier)
            .clickable(
                onClick = standaloneTab.onClick,
                indication = standaloneTab.indication?.invoke(),
                interactionSource = remember { MutableInteractionSource() }
            )
            .then(if (contentPadding != null) Modifier.padding(contentPadding) else Modifier)
    )
}

@Composable
private fun Tab(
    icon: @Composable () -> Unit,
    title: @Composable () -> Unit,
    isInline: Boolean,
    modifier: Modifier = Modifier,
    isStandalone: Boolean = false
) {
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        icon()
        if (!isStandalone && !isInline) {
            title()
        }
    }
}

/**
 * A custom modifier that provides smooth enter/exit animations without clipping shadows or other content.
 * This is an alternative to animateEnterExit that uses renderInSharedTransitionScopeOverlay to prevent clipping.
 */
@Composable
private fun Modifier.animateEnterExitAccessory(
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope
): Modifier = with(sharedTransitionScope) {
    with(animatedVisibilityScope) {
        val animatedAlpha by transition.animateFloat { targetState ->
            when (targetState) {
                EnterExitState.Visible -> 1f
                else -> 0f
            }
        }

        this@animateEnterExitAccessory
            .renderInSharedTransitionScopeOverlay()
            .graphicsLayer(
                compositingStrategy = CompositingStrategy.ModulateAlpha,
                alpha = animatedAlpha
            )
    }
}

/**
 * A custom modifier that provides smooth enter/exit animations with fade and blur effects.
 */
@Composable
private fun Modifier.animateEnterExitTab(
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope
): Modifier = with(sharedTransitionScope) {
    with(animatedVisibilityScope) {
        val enterStartFraction = 0.5f
        val enterEndFraction = 0.8f
        val durationMs = 150

        val animatedAlpha by transition.animateFloat(
            transitionSpec = {
                keyframes {
                    durationMillis = durationMs
                    if (targetState == EnterExitState.Visible) {
                        0f atFraction enterStartFraction using FastOutSlowInEasing
                        1f atFraction enterEndFraction
                    }
                }
            }
        ) { targetState ->
            when (targetState) {
                EnterExitState.Visible -> 1f
                else -> 0f
            }
        }

        val blurRadius = with(LocalDensity.current) { 50.dp.toPx() }
        val animatedBlur by transition.animateFloat(
            transitionSpec = {
                keyframes {
                    durationMillis = durationMs
                    if (targetState == EnterExitState.Visible) {
                        blurRadius atFraction enterStartFraction using FastOutSlowInEasing
                        0f atFraction enterEndFraction
                    }
                }
            }
        ) { targetState ->
            when (targetState) {
                EnterExitState.Visible -> 0f
                else -> blurRadius
            }
        }

        graphicsLayer {
            alpha = animatedAlpha
            // LOCAL CHANGE: no effect at all while there is nothing to blur.
            //
            // Upstream builds one whatever the radius is, and a tab that is
            // not animating asks for zero. Most phones treat that as a blur
            // that does nothing; some answer with no effect at all, and the
            // platform turns that into "nativePtr is null" the moment the bar
            // is drawn, which killed the app on a moto g24 power every time
            // (#24). The two other blurs in this app already ask this
            // question; see Blur.kt and InnerShadowModifier.
            renderEffect = if (animatedBlur > 0f) {
                BlurEffect(radiusX = animatedBlur, radiusY = animatedBlur)
            } else {
                null
            }
        }
    }
}

private class FloatingTabBarScopeImpl : FloatingTabBarScope {
    val tabs = mutableStateListOf<FloatingTabBarTab>()
    var standaloneTab: FloatingTabBarTab? by mutableStateOf(null)
        private set
    private var inlineTab: FloatingTabBarTab? = null

    fun getInlineTab(selectedTabKey: Any?): FloatingTabBarTab? {
        return if (selectedTabKey != standaloneTab?.key) {
            val selectedTab = tabs.find { it.key == selectedTabKey }
            if (selectedTab != null) {
                inlineTab = selectedTab
                selectedTab
            } else {
                inlineTab ?: tabs.firstOrNull()
            }
        } else {
            inlineTab ?: tabs.firstOrNull()
        }
    }

    override fun tab(
        key: Any,
        title: @Composable () -> Unit,
        icon: @Composable () -> Unit,
        onClick: () -> Unit,
        indication: (@Composable () -> Indication)?
    ) {
        tabs.add(
            FloatingTabBarTab(
                key = key,
                title = title,
                icon = icon,
                onClick = onClick,
                indication = indication
            )
        )
    }
    
    override fun standaloneTab(
        key: Any,
        icon: @Composable () -> Unit,
        onClick: () -> Unit,
        indication: (@Composable () -> Indication)?
    ) {
        standaloneTab = FloatingTabBarTab(
            key = key,
            title = {},
            icon = icon,
            onClick = onClick,
            indication = indication
        )
    }
}

private data class FloatingTabBarTab(
    val key: Any,
    val title: @Composable () -> Unit,
    val icon: @Composable () -> Unit,
    val onClick: () -> Unit,
    val indication: (@Composable () -> Indication)?
)

/**
 * Represents the colors used in [FloatingTabBar].
 */
@Immutable
data class FloatingTabBarColors(
    val backgroundColor: Color,
    val accessoryBackgroundColor: Color,
    val selectedTabBackgroundColor: Color,
    val selectedStandaloneTabBackgroundColor: Color,
)

/**
 * Represents the shapes used in [FloatingTabBar].
 */
@Immutable
data class FloatingTabBarShapes(
    val tabBarShape: Shape,
    val tabShape: Shape,
    val standaloneTabShape: Shape,
    val accessoryShape: Shape,
)

/**
 * Represents the elevations used in [FloatingTabBar].
 */
@Immutable
data class FloatingTabBarElevations(
    val inlineElevation: Dp,
    val expandedElevation: Dp,
)

/**
 * Represents the sizes and spacing used in [FloatingTabBar].
 */
@Immutable
data class FloatingTabBarSizes(
    val tabBarContentPadding: PaddingValues,
    val tabInlineContentPadding: PaddingValues,
    val tabExpandedContentPadding: PaddingValues,
    val componentSpacing: Dp,
    val tabSpacing: Dp,
)

/**
 * Contains the default values used by [FloatingTabBar].
 */
object FloatingTabBarDefaults {
    /**
     * Creates a [FloatingTabBarColors] that represents the default colors used in a [FloatingTabBar].
     *
     * @param backgroundColor the color used for the tab bar background
     * @param accessoryBackgroundColor the color used for the accessory background
     * @param selectedTabBackgroundColor the color used for the selected tab background in expanded state
     * @param selectedStandaloneTabBackgroundColor the color used for the selected standalone tab background in expanded state
     */
    @Composable
    fun colors(
        backgroundColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
        accessoryBackgroundColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
        selectedTabBackgroundColor: Color = Color.Transparent,
        selectedStandaloneTabBackgroundColor: Color = Color.Transparent,
    ): FloatingTabBarColors = FloatingTabBarColors(
        backgroundColor = backgroundColor,
        accessoryBackgroundColor = accessoryBackgroundColor,
        selectedTabBackgroundColor = selectedTabBackgroundColor,
        selectedStandaloneTabBackgroundColor = selectedStandaloneTabBackgroundColor,
    )

    /**
     * Creates a [FloatingTabBarShapes] that represents the default shapes used in a [FloatingTabBar].
     *
     * @param tabBarShape the shape used to clip the tab bar
     * @param tabShape the shape used to clip individual tabs. Can be useful for example to control the click ripple effect shape
     * @param standaloneTabShape the shape used to clip the standalone tab
     * @param accessoryShape the shape used to clip the accessory container
     */
    @Composable
    fun shapes(
        tabBarShape: Shape = RoundedCornerShape(100),
        tabShape: Shape = RoundedCornerShape(100),
        standaloneTabShape: Shape = CircleShape,
        accessoryShape: Shape = RoundedCornerShape(100),
    ): FloatingTabBarShapes = FloatingTabBarShapes(
        tabBarShape = tabBarShape,
        tabShape = tabShape,
        standaloneTabShape = standaloneTabShape,
        accessoryShape = accessoryShape,
    )

    /**
     * Creates a [FloatingTabBarSizes] that represents the default sizes used in a [FloatingTabBar].
     *
     * @param tabBarContentPadding the padding applied to the tab bar content. This also applies to the standalone tab content.
     * @param tabInlineContentPadding the padding applied to tabs in inline state
     * @param tabExpandedContentPadding the padding applied to tabs in expanded state
     * @param componentSpacing the spacing between components
     * @param tabSpacing the spacing between tabs in expanded state
     */
    @Composable
    fun sizes(
        tabBarContentPadding: PaddingValues = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
        tabInlineContentPadding: PaddingValues = PaddingValues(10.dp),
        tabExpandedContentPadding: PaddingValues = PaddingValues(vertical = 6.dp, horizontal = 20.dp),
        componentSpacing: Dp = 8.dp,
        tabSpacing: Dp = 0.dp,
    ): FloatingTabBarSizes = FloatingTabBarSizes(
        tabBarContentPadding = tabBarContentPadding,
        tabInlineContentPadding = tabInlineContentPadding,
        tabExpandedContentPadding = tabExpandedContentPadding,
        componentSpacing = componentSpacing,
        tabSpacing = tabSpacing,
    )

    /**
     * Creates a [FloatingTabBarElevations] that represents the default elevations used in a [FloatingTabBar].
     *
     * @param inlineElevation the elevation used for tabs in inline state
     * @param expandedElevation the elevation used for tabs in expanded state
     */
    @Composable
    fun elevations(
        inlineElevation: Dp = 6.dp,
        expandedElevation: Dp = 12.dp,
    ): FloatingTabBarElevations = FloatingTabBarElevations(
        inlineElevation = inlineElevation,
        expandedElevation = expandedElevation,
    )
}