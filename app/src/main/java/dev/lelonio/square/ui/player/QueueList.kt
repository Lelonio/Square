package dev.lelonio.square.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.bold.Check
import com.adamglin.phosphoricons.regular.DotsSixVertical
import com.adamglin.phosphoricons.regular.Sparkle
import com.adamglin.phosphoricons.regular.Trash
import com.adamglin.phosphoricons.regular.X
import dev.lelonio.square.R
import dev.lelonio.square.ui.glass.pressable
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** What the queue can do to the player; see SquareApp, which holds the player. */
class QueueActions(
    val onPlay: (Int) -> Unit,
    /** Player indices, in any order. */
    val onRemove: (List<Int>) -> Unit,
    /** From one player index to another. */
    val onMove: (Int, Int) -> Unit,
    /** Player indices, moved to just after the playing track, in their order. */
    val onPlayNext: (List<Int>) -> Unit,
    /** Puts back what was last taken out, while that is still possible. */
    val onUndo: (() -> Unit)? = null,
    /**
     * False while another device is playing: its queue is shown, and Spotify
     * does not let it be rearranged from here.
     */
    val editable: Boolean = true,
)

/**
 * The queue, as the big streaming apps have it.
 *
 * What was heard, above, for whoever scrolls up; the track playing; what was
 * queued by hand, with a way to clear it; and what follows from the playlist
 * or album. Every upcoming track can be dragged by its handle, swiped away,
 * or long-pressed to select several and move or remove them together.
 *
 * Rows are keyed on the track and how many times it has come before, never on
 * where they sit: keyed on the position, every change to the queue made every
 * row below it a new one, and the list drew the old and the new over each
 * other while it animated between them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QueueList(queue: List<QueueEntry>, actions: QueueActions) {
    if (queue.isEmpty()) {
        EmptyPanel(stringResource(R.string.queue_empty))
        return
    }

    val occurrences = remember(queue) {
        val seen = HashMap<String, Int>()
        queue.map { entry ->
            val n = seen[entry.uri] ?: 0
            seen[entry.uri] = n + 1
            n
        }
    }
    fun keyOf(position: Int) = "${queue[position].uri}#${occurrences[position]}"

    val currentPosition = queue.indexOfFirst { it.isCurrent }
    val history = queue.filter { it.played }
    val current = queue.getOrNull(currentPosition)

    // The upcoming tracks as this screen holds them while one is being
    // dragged: moved here at every step, given to the player once on release.
    var upcoming by remember(queue) {
        mutableStateOf(
            queue.indices.filter { it > currentPosition && !queue[it].played }.map { keyOf(it) to queue[it] },
        )
    }
    var dragging by remember { mutableStateOf<String?>(null) }
    var selected by remember(queue) { mutableStateOf(emptySet<String>()) }
    val selecting = selected.isNotEmpty()
    val haptics = LocalHapticFeedback.current
    val dismissLock = LocalDismissLock.current
    // Closed mid-drag, the sheet would otherwise stay unable to close.
    DisposableEffect(Unit) { onDispose { dismissLock.value = false } }

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = if (history.isNotEmpty()) history.size + 1 else 0,
    )
    LaunchedEffect(current?.uri, history.size) {
        listState.scrollToItem(if (history.isNotEmpty()) history.size + 1 else 0)
    }
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val fromAt = upcoming.indexOfFirst { it.first == from.key }
        val toAt = upcoming.indexOfFirst { it.first == to.key }
        // Only among the upcoming tracks: headings, the playing track and the
        // history are not places a track can be dropped.
        if (fromAt < 0 || toAt < 0) return@rememberReorderableLazyListState
        upcoming = upcoming.toMutableList().apply { add(toAt, removeAt(fromAt)) }
    }

    // Where the upcoming tracks begin in the player's own order.
    val firstUpcomingIndex = (current?.index ?: -1) + 1
    val queuedCount = upcoming.count { it.second.queued }
    val contextLabel = upcoming.firstOrNull { !it.second.queued }?.second?.contextLabel
        ?: current?.contextLabel.orEmpty()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.padding(vertical = 8.dp), state = listState) {
            if (history.isNotEmpty()) {
                item(key = "history-heading") { Heading(stringResource(R.string.queue_history)) }
                items(queue.indices.filter { queue[it].played }, key = { "played:" + keyOf(it) }) { at ->
                    TrackRow(queue[at], actions, selected = false, selecting = false, onLongPress = null, trailing = {})
                }
            }

            if (current != null) {
                item(key = "now-heading") { Heading(stringResource(R.string.queue_now_playing)) }
                item(key = "now:" + keyOf(currentPosition)) {
                    TrackRow(current, actions, selected = false, selecting = false, onLongPress = null, trailing = {})
                }
            }

            upcoming.forEachIndexed { position, (key, entry) ->
                // A heading where a section starts: the hand-queued tracks
                // first, then what follows from the playlist.
                val previous = upcoming.getOrNull(position - 1)?.second
                if (entry.queued && (previous == null || !previous.queued)) {
                    item(key = "queued-heading-$position") {
                        Heading(
                            stringResource(R.string.queue_next_in_queue),
                            action = if (actions.editable && !selecting) stringResource(R.string.queue_clear) else null,
                            onAction = {
                                actions.onRemove(
                                    upcoming.mapIndexedNotNull { at, (_, it) ->
                                        (firstUpcomingIndex + at).takeIf { _ -> it.queued }
                                    },
                                )
                            },
                        )
                    }
                }
                if (!entry.queued && (previous == null || previous.queued)) {
                    item(key = "context-heading-$position") {
                        Heading(
                            if (contextLabel.isNotEmpty()) {
                                stringResource(R.string.queue_next_from, contextLabel)
                            } else {
                                stringResource(R.string.queue_next_up)
                            },
                        )
                    }
                }
                item(key = key) {
                    ReorderableItem(reorder, key = key) { isDragging ->
                        // Lifted on a tinted card of its own: a shadow under a
                        // see-through row drew a dark box behind its text.
                        val lift by animateFloatAsState(if (isDragging) 1f else 0f, label = "queue lift")
                        SwipeAway(
                            enabled = actions.editable && !selecting && dragging == null,
                            onSwiped = { actions.onRemove(listOf(entry.index)) },
                        ) {
                            TrackRow(
                                entry = entry,
                                actions = actions,
                                selected = key in selected,
                                selecting = selecting,
                                modifier = Modifier
                                    .padding(horizontal = (8 * lift).dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(GlassInk.copy(alpha = 0.14f * lift)),
                                onLongPress = if (actions.editable) {
                                    {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        selected = selected + key
                                    }
                                } else {
                                    null
                                },
                                onToggle = { selected = if (key in selected) selected - key else selected + key },
                                trailing = {
                                    if (actions.editable && !selecting) {
                                        Icon(
                                            PhosphorIcons.Regular.DotsSixVertical,
                                            contentDescription = stringResource(R.string.queue_reorder),
                                            tint = GlassInkDim,
                                            modifier = Modifier
                                                .draggableHandle(
                                                    onDragStarted = {
                                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        dragging = key
                                                        dismissLock.value = true
                                                    },
                                                    onDragStopped = {
                                                        val now = upcoming.indexOfFirst { it.first == key }
                                                        dragging = null
                                                        dismissLock.value = false
                                                        if (now >= 0 && now != position) {
                                                            actions.onMove(entry.index, firstUpcomingIndex + now)
                                                        }
                                                    },
                                                )
                                                .padding(10.dp)
                                                .size(20.dp),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // Room for the bars that float over the bottom of the list.
            item(key = "end") { Box(Modifier.padding(bottom = if (selecting || actions.onUndo != null) 72.dp else 8.dp)) }
        }

        // Several selected: what can be done to them all at once.
        AnimatedVisibility(
            visible = selecting,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val indices = upcoming.mapIndexedNotNull { at, (key, _) ->
                (firstUpcomingIndex + at).takeIf { key in selected }
            }
            ActionBar(
                label = pluralStringResource(R.plurals.queue_selected, selected.size, selected.size),
                actions = listOf(
                    stringResource(R.string.queue_play_next) to {
                        actions.onPlayNext(indices)
                        selected = emptySet()
                    },
                    stringResource(R.string.queue_remove) to {
                        actions.onRemove(indices)
                        selected = emptySet()
                    },
                    stringResource(R.string.cancel) to { selected = emptySet() },
                ),
            )
        }

        // Taken out by mistake: put back, for a few seconds.
        AnimatedVisibility(
            visible = !selecting && actions.onUndo != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ActionBar(
                label = stringResource(R.string.queue_removed),
                actions = listOf(stringResource(R.string.queue_undo) to { actions.onUndo?.invoke(); Unit }),
            )
        }
    }
}

@Composable
private fun Heading(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = GlassInkDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            Text(
                action,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = GlassInk,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
    entry: QueueEntry,
    actions: QueueActions,
    selected: Boolean,
    selecting: Boolean,
    onLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit = {},
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(if (selected) GlassInk.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(
                onClick = { if (selecting) onToggle() else actions.onPlay(entry.index) },
                onLongClick = onLongPress,
            )
            .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Box(
                Modifier
                    .padding(end = 12.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (selected) GlassInk else GlassInk.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        PhosphorIcons.Bold.Check,
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color.Black,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    entry.isCurrent -> GlassInk
                    entry.played -> GlassInk.copy(alpha = 0.45f)
                    else -> GlassInk.copy(alpha = 0.85f)
                },
                fontWeight = if (entry.isCurrent) FontWeight.SemiBold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.recommended) {
                    Icon(
                        PhosphorIcons.Regular.Sparkle,
                        contentDescription = stringResource(R.string.smart_shuffle),
                        tint = GlassInkDim,
                        modifier = Modifier.padding(end = 4.dp).size(12.dp),
                    )
                }
                Text(
                    entry.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = GlassInkDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}

/** Swiped towards the start, a row goes, over a red ground with a bin on it. */
@Composable
private fun SwipeAway(enabled: Boolean, onSwiped: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = enabled,
        gesturesEnabled = enabled,
        onDismiss = { value -> if (value == SwipeToDismissBoxValue.EndToStart) onSwiped() },
        backgroundContent = {
            // Only where the row has moved off: the rows are see-through, over
            // the glass, so a ground behind the whole row showed through it
            // before it was ever swiped.
            val revealed = with(LocalDensity.current) {
                (-(runCatching { state.requireOffset() }.getOrDefault(0f))).coerceAtLeast(0f).toDp()
            }
            Box(Modifier.fillMaxSize()) {
                if (revealed > 0.dp) {
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(revealed)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (revealed > 44.dp) {
                            Icon(
                                PhosphorIcons.Regular.Trash,
                                contentDescription = stringResource(R.string.remove_from_queue),
                                tint = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        },
    ) { content() }
}

@Composable
private fun ActionBar(label: String, actions: List<Pair<String, () -> Unit>>) {
    Row(
        Modifier
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(GlassInk.copy(alpha = 0.92f))
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val ground = MaterialTheme.colorScheme.surface
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = ground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions.forEach { (text, onClick) ->
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = ground,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .pressable(onClick, pressedScale = 0.94f)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}
