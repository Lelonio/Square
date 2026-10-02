@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package dev.lelonio.square.ui.glass.floatingtabbar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt

internal val LocalGeometricFloatingTabBar = staticCompositionLocalOf { false }
internal val LocalFloatingTabBarCollapseProgress = staticCompositionLocalOf<(() -> Float)?> { null }

/**
 * One coordinate system and one clock for the fold. The tab content is measured
 * at its resting width; only its surface shrinks. Search and the player are
 * single persistent nodes, so neither gets a new destination from a moving
 * shared-element parent. All coordinates below are relative to the bottom row.
 */
@Composable
internal fun SharedTransitionScope.GeometricFloatingTabBar(
    scope: FloatingTabBarScopeImpl,
    selectedTabKey: Any?,
    isInline: Boolean,
    searchMode: Boolean,
    onExpand: () -> Unit,
    expandedTabs: @Composable SharedTransitionScope.(Modifier, androidx.compose.animation.AnimatedVisibilityScope) -> Unit,
    accessory: (@Composable SharedTransitionScope.(Modifier, androidx.compose.animation.AnimatedVisibilityScope) -> Unit)?,
    searchBarContent: (@Composable (Modifier) -> Unit)?,
    rowHeight: Dp,
    tabBarContentModifier: Modifier,
    colors: FloatingTabBarColors,
    shapes: FloatingTabBarShapes,
    sizes: FloatingTabBarSizes,
) {
    val fold = animateFloatAsState(
        if (isInline) 1f else 0f,
        tween(320, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
        label = "barGeometryFold",
    )
    val search = animateFloatAsState(
        if (searchMode) 1f else 0f,
        tween(320, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
        label = "barGeometrySearch",
    )
    val collapseProgress = remember(fold) { { fold.value } }
    val selected = scope.getInlineTab(selectedTabKey)
    val searchTab = scope.standaloneTab ?: scope.tabs.lastOrNull()
    val searchTitle = scope.tabs.lastOrNull()?.title
    val selectedIndex = scope.tabs.indexOf(selected).coerceAtLeast(0)
    val tabsCount = scope.tabs.size.coerceAtLeast(1)
    val ownsSelectedSlot = selected?.key == selectedTabKey
    val sharedScope = this

    // This visibility scope only satisfies the existing content contract. It
    // never changes visibility and does not participate in the fold.
    AnimatedVisibility(visible = true) {
        val visibility = this
        Layout(
            modifier = Modifier.fillMaxWidth(),
            content = {
                // 0: the single navigation surface.
                Box(Modifier.background(colors.backgroundColor, shapes.tabBarShape)
                    .clip(shapes.tabBarShape).then(tabBarContentModifier))
                // 1: resting-width tabs, fading into the selected icon.
                Box(Modifier.graphicsLayer {
                    alpha = (1f - fold.value * 3f).coerceIn(0f, 1f) * (1f - search.value)
                }.drawWithContent {
                    val p = fold.value
                    val width = lerp(lerp(size.width, size.height, search.value), size.height, p)
                    val contentScope = this
                    clipRect(right = width) { contentScope.drawContent() }
                }.then(if (isInline || searchMode) Modifier.clearAndSetSemantics {} else Modifier)) {
                    CompositionLocalProvider(LocalGeometricFloatingTabBar provides true) {
                        sharedScope.expandedTabs(Modifier.fillMaxWidth(), visibility)
                    }
                }
                // 2: one selected control, including its label at rest.
                Box(Modifier.graphicsLayer {
                    alpha = if (ownsSelectedSlot) 1f else maxOf(fold.value, search.value)
                }.then(if (isInline || searchMode) Modifier.clickable {
                    if (isInline) onExpand() else selected?.onClick?.invoke()
                } else Modifier).then(if (!ownsSelectedSlot && !isInline && !searchMode) Modifier.clearAndSetSemantics {} else Modifier)) {
                    GeometricTabContent(
                        icon = { selected?.icon?.invoke() },
                        title = { selected?.title?.invoke() },
                        titleFraction = { (1f - fold.value) * (1f - search.value) },
                    )
                }
                // 3: search's glass exists only while detached from navigation.
                Box(Modifier.graphicsLayer { alpha = maxOf(fold.value, search.value) }
                    .background(colors.backgroundColor, shapes.standaloneTabShape)
                    .clip(shapes.standaloneTabShape).then(tabBarContentModifier))
                // 4: one magnifier and label, sharing the surface's geometry.
                Box(Modifier.graphicsLayer { alpha = 1f - search.value * (1f - fold.value) }
                    .clickable(enabled = !searchMode || isInline) { searchTab?.onClick?.invoke() }
                    .then(if (searchMode && !isInline) Modifier.clearAndSetSemantics {} else Modifier)) {
                    GeometricTabContent(
                        icon = { searchTab?.icon?.invoke() },
                        title = { searchTitle?.invoke() },
                        titleFraction = { 1f - fold.value },
                    )
                }
                // 5: search field replaces the magnifier when entering search.
                Box(Modifier.graphicsLayer { alpha = search.value * (1f - fold.value) }
                    .then(if (!searchMode || isInline) Modifier.clearAndSetSemantics {} else Modifier)) {
                    if (searchMode || search.value > 0f) {
                        searchBarContent?.invoke(Modifier.fillMaxWidth().height(rowHeight))
                    }
                }
                // 6: one player whose width, height and content all use fold.
                Box {
                    if (accessory != null) {
                        CompositionLocalProvider(LocalFloatingTabBarCollapseProgress provides collapseProgress) {
                            sharedScope.accessory(
                                Modifier.fillMaxWidth().background(colors.accessoryBackgroundColor, shapes.accessoryShape)
                                    .clip(shapes.accessoryShape), visibility,
                            )
                        }
                    }
                }
            },
        ) { children, constraints ->
            val width = constraints.maxWidth
            val expandedHeight = rowHeight.roundToPx()
            // 36dp artwork plus 6dp padding above and below in the compact player.
            val compactHeight = if (accessory != null) 48.dp.roundToPx() else expandedHeight
            val spacing = sizes.componentSpacing.roundToPx()
            val p = fold.value
            val s = search.value
            val height = lerp(expandedHeight.toFloat(), compactHeight.toFloat(), p).roundToInt()
            val circle = height.coerceAtMost(width)
            val playerWidth = lerp(width.toFloat(), (width - 2 * (compactHeight + spacing)).coerceAtLeast(0).toFloat(), p).roundToInt()
            val player = children[6].measure(Constraints(minWidth = playerWidth, maxWidth = playerWidth))
            val geometry = foldGeometry(width, expandedHeight, spacing, player.height, accessory != null,
                p, s, tabsCount, selectedIndex, 4.dp.toPx(), compactHeight)
            val totalHeight = geometry.height
            val rowY = geometry.rowY
            val navWidth = geometry.navWidth
            val surface = children[0].measure(Constraints.fixed(navWidth, height))
            val tabs = children[1].measure(Constraints.fixed(width, height))
            val selectedButton = children[2].measure(Constraints.fixed(circle, height))
            val searchWidth = geometry.searchWidth
            val searchX = geometry.searchX
            val searchSurface = children[3].measure(Constraints.fixed(searchWidth, height))
            val searchControl = children[4].measure(Constraints.fixed(circle, height))
            val field = children[5].measure(Constraints.fixed(searchWidth, height))
            val selectedX = geometry.selectedX
            val playerX = geometry.playerX
            val playerY = geometry.playerY
            layout(width, totalHeight) {
                surface.placeRelative(0, rowY)
                tabs.placeRelative(0, rowY)
                selectedButton.placeRelative(selectedX, rowY)
                searchSurface.placeRelative(searchX, rowY)
                searchControl.placeRelative(searchX, rowY)
                field.placeRelative(searchX, rowY)
                player.placeRelative(playerX, playerY)
            }
        }
    }
}

@Composable
private fun GeometricTabContent(
    icon: @Composable () -> Unit,
    title: @Composable () -> Unit,
    titleFraction: () -> Float,
) {
    Layout(content = {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) { icon() }
        Box(Modifier.graphicsLayer { alpha = titleFraction() }) { title() }
    }) { children, constraints ->
        val iconPlaceable = children[0].measure(Constraints.fixed(26.dp.roundToPx(), 26.dp.roundToPx()))
        val titlePlaceable = children[1].measure(Constraints(maxWidth = constraints.maxWidth))
        val gap = 2.dp.roundToPx()
        val fullTop = (constraints.maxHeight - iconPlaceable.height - gap - titlePlaceable.height) / 2f
        val top = lerp((constraints.maxHeight - iconPlaceable.height) / 2f, fullTop, titleFraction()).roundToInt()
        layout(constraints.maxWidth, constraints.maxHeight) {
            iconPlaceable.placeRelative((constraints.maxWidth - iconPlaceable.width) / 2, top)
            titlePlaceable.placeRelative((constraints.maxWidth - titlePlaceable.width) / 2, top + iconPlaceable.height + gap)
        }
    }
}
