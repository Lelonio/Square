package dev.lelonio.square.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lelonio.square.R
import dev.lelonio.square.data.Lyrics
import dev.lelonio.square.playback.EffectPreset
import androidx.compose.foundation.layout.RowScope
import dev.lelonio.square.ui.glass.LiquidBottomTab
import dev.lelonio.square.ui.glass.LiquidBottomTabs
import dev.lelonio.square.ui.glass.LiquidSlider
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.SlidersHorizontal
import com.adamglin.phosphoricons.regular.TextAlignLeft
import com.adamglin.phosphoricons.fill.SlidersHorizontal
import com.adamglin.phosphoricons.fill.TextAlignLeft
import com.adamglin.phosphoricons.fill.VinylRecord
import com.adamglin.phosphoricons.regular.VinylRecord
import com.adamglin.phosphoricons.fill.Info
import com.adamglin.phosphoricons.regular.Info
import com.adamglin.phosphoricons.regular.X
import dev.lelonio.square.ui.glass.pressable

/** Which panel is open below the transport controls. */
enum class PlayerPanel {
    NONE,
    QUEUE,
    LYRICS,
    EFFECTS,

    /** Who made the track: performers, writers, producers, and the label. */
    INFO,

    /**
     * The Connect device list and the playlist picker.
     *
     * Both used to open as modals over the transport. The player already has one
     * place where a second thing is shown — the slot the cover lives in — and
     * two mechanisms for the same job meant the screen sometimes dimmed itself
     * and sometimes did not, depending on which button had been pressed.
     */
    DEVICES,
    ADD_TO_PLAYLIST,
}

@Composable
fun PlayerPanelSection(
    panel: PlayerPanel,
    onSelect: (PlayerPanel) -> Unit,
    queue: List<QueueEntry>,
    lyrics: Lyrics?,
    lyricsLoading: Boolean,
    positionMs: androidx.compose.runtime.State<Long>,
    onPlayQueueItem: (Int) -> Unit,
    onSeek: (Long) -> Unit,
    speed: Float,
    pitch: Float,
    reverb: Float,
    onSpeed: (Float) -> Unit,
    onPitch: (Float) -> Unit,
    onReverb: (Float) -> Unit,
    presets: List<EffectPreset>,
    onApplyPreset: (EffectPreset) -> Unit,
    onSavePreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
    /** The layer the panel refracts; see the liquid-glass note in PlayerScreen. */
    backdrop: dev.lelonio.square.ui.glass.backdrop.Backdrop,
) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The same segmented control the tab bar uses, because this is the same
        // kind of choice: three views of one screen, exactly one of them
        // showing. Three separate toggles said "three independent switches",
        // which is not what cover, lyrics and effects are.
        //
        // The queue is deliberately not in here — it is a sheet that opens over
        // the player rather than a view of it, and it has its own button beside
        // the title.
        // Whether anything is being done to the sound right now.
        val speed by dev.lelonio.square.playback.AudioEffects.speed
            .collectAsStateWithLifecycle()
        val pitch by dev.lelonio.square.playback.AudioEffects.pitch
            .collectAsStateWithLifecycle()
        val reverb by dev.lelonio.square.playback.AudioEffects.reverb
            .collectAsStateWithLifecycle()
        val effectsOn = speed != 1f || pitch != 1f || reverb > 0f
        val karaoke by dev.lelonio.square.playback.AudioEffects.karaoke
            .collectAsStateWithLifecycle()
        val karaokeOn = karaoke > 0f

        val views = remember {
            listOf(PlayerPanel.NONE, PlayerPanel.LYRICS, PlayerPanel.EFFECTS, PlayerPanel.INFO)
        }
        val selected = views.indexOf(panel).coerceAtLeast(0)
        // Stable, or LiquidBottomTabs throws away the state it keys on this and
        // the indicator stops animating; see the note in SquareApp.
        val selectedState = rememberUpdatedState(selected)
        val selectedTabIndex = remember { { selectedState.value } }

        LiquidBottomTabs(
            selectedTabIndex = selectedTabIndex,
            onTabSelected = { onSelect(views[it]) },
            backdrop = backdrop,
            tabsCount = views.size,
            accentColor = GlassInk,
            containerColor = LocalPlayerFilm.current,
            // Slimmer than the tab bar, and icon-only. This one sits under the
            // transport rather than at the edge of the window, so it has to
            // read as a smaller thing than the app's own navigation.
            height = 42.dp,
            // Under half the height, or the refraction from the two long edges
            // meets in the middle and draws a seam across the capsule.
            lensDepth = 12.dp,
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .padding(top = 14.dp),
        ) {
            PanelTab(
                icon = PhosphorIcons.Regular.VinylRecord,
                activeIcon = PhosphorIcons.Fill.VinylRecord,
                label = stringResource(R.string.cover),
                selected = selected == 0,
            ) { onSelect(PlayerPanel.NONE) }
            PanelTab(
                icon = PhosphorIcons.Regular.TextAlignLeft,
                activeIcon = PhosphorIcons.Fill.TextAlignLeft,
                label = stringResource(R.string.lyrics),
                selected = selected == 1,
                // The karaoke lives in this view and keeps working with the
                // panel shut; see the same halo on the effects tab.
                marked = karaokeOn,
            ) { onSelect(PlayerPanel.LYRICS) }
            PanelTab(
                icon = PhosphorIcons.Regular.SlidersHorizontal,
                activeIcon = PhosphorIcons.Fill.SlidersHorizontal,
                label = stringResource(R.string.effects),
                selected = selected == 2,
                marked = effectsOn,
            ) { onSelect(PlayerPanel.EFFECTS) }
            PanelTab(
                icon = PhosphorIcons.Regular.Info,
                activeIcon = PhosphorIcons.Fill.Info,
                label = stringResource(R.string.credits),
                selected = selected == 3,
            ) { onSelect(PlayerPanel.INFO) }
        }

    }
}

@Composable
private fun RowScope.PanelTab(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    /** The filled cut of the same glyph, for the view being shown. */
    activeIcon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    /**
     * A light on the tab: what is behind it is doing something right now.
     *
     * Used by the effects, which are the one view whose settings go on working
     * after it is closed — a song playing a third slower with the panel shut
     * looks, from this row, exactly like a song playing normally.
     */
    marked: Boolean = false,
    onClick: () -> Unit,
) {
    LiquidBottomTab(onClick = onClick) {
        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
        if (marked) {
            // A halo rather than a badge stuck to the corner: the tabs are
            // small and round-ended, and a dot on the edge of one reads as
            // damage to the capsule.
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(30.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            listOf(GlassInk.copy(alpha = 0.28f), androidx.compose.ui.graphics.Color.Transparent),
                        ),
                        androidx.compose.foundation.shape.CircleShape,
                    ),
            )
        }
        Icon(
            // Filled rather than only brighter. The indicator behind the icon
            // moves, so at a glance the two states differed by a shade of grey
            // sliding around; a solid glyph says which view you are in without
            // being read against its neighbours.
            imageVector = if (selected) activeIcon else icon,
            contentDescription = label,
            tint = when {
                selected -> GlassInk
                marked -> GlassInk.copy(alpha = 0.85f)
                else -> GlassInkDim
            },
            modifier = Modifier.size(19.dp),
        )
        }
    }
}

/** One row of the upcoming-tracks list. */
data class QueueEntry(
    val index: Int,
    /** What the row is, so a list that loses one can animate the rest. */
    val uri: String,
    val title: String,
    val artist: String,
    val isCurrent: Boolean,
    /** Already heard: shown above the playing track, dimmed. */
    val played: Boolean = false,
)

@Composable
internal fun QueueList(
    queue: List<QueueEntry>,
    onPlay: (Int) -> Unit,
    /** Takes a track out of the queue; absent for the one playing. */
    onRemove: (Int) -> Unit,
) {
    if (queue.isEmpty()) {
        EmptyPanel(stringResource(R.string.queue_empty))
        return
    }

    // Opens on the playing track, with what was heard above it out of view:
    // history for whoever scrolls up, and the next songs where they always
    // were for everyone else. Back to it on every change of song, as the list
    // used to start there by having nothing before it.
    val heard = queue.count { it.played }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = if (heard > 0) heard + 1 else 0,
    )
    val playingUri = queue.firstOrNull { it.isCurrent }?.uri
    LaunchedEffect(playingUri, heard) {
        listState.scrollToItem(if (heard > 0) heard + 1 else 0)
    }

    LazyColumn(Modifier.padding(vertical = 8.dp), state = listState) {
        if (heard > 0) {
            item(key = "history-heading") {
                Text(
                    stringResource(R.string.queue_history),
                    style = MaterialTheme.typography.labelLarge,
                    color = GlassInkDim,
                    modifier = Modifier.padding(start = 18.dp, top = 6.dp, bottom = 4.dp),
                )
            }
        }
        // Keyed on the track, not on where it sits.
        //
        // With the index as the key, taking one out renumbered every row below
        // it — as far as the list is concerned each of those became a different
        // item, so nothing could be animated and the queue jumped. Keyed on the
        // track itself, the row that went is the only one that changes and the
        // rest slide up into the gap.
        itemsIndexed(
            queue,
            key = { at, entry -> "${entry.uri}-$at-${entry.title}" },
        ) { _, entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .animateItem()
                    .clickable { onPlay(entry.index) }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = when {
                            entry.isCurrent -> GlassInk
                            entry.played -> GlassInk.copy(alpha = 0.45f)
                            else -> GlassInk.copy(alpha = 0.85f)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        entry.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = GlassInkDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Not on the track being played: taking that one out is a
                // different act — it is a skip — and it already has a button.
                if (!entry.isCurrent && !entry.played) {
                    Icon(
                        PhosphorIcons.Regular.X,
                        contentDescription = stringResource(R.string.remove_from_queue),
                        tint = GlassInkDim,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .pressable({ onRemove(entry.index) }, pressedScale = 0.86f)
                            .padding(10.dp)
                            .size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * Tempo, pitch and reverb.
 *
 * Speed and pitch are separate controls on purpose: resampling would move both
 * at once, and the whole point of asking for them separately is being able to
 * slow a track down without dropping it an octave. The platform's time
 * stretcher does the work — see [dev.lelonio.square.playback.AudioOutput].
 */
@Composable
internal fun EffectsPanel(
    speed: Float,
    pitch: Float,
    reverb: Float,
    onSpeed: (Float) -> Unit,
    onPitch: (Float) -> Unit,
    onReverb: (Float) -> Unit,
    presets: List<EffectPreset>,
    onApplyPreset: (EffectPreset) -> Unit,
    onSavePreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
    backdrop: dev.lelonio.square.ui.glass.backdrop.Backdrop,
) {
    var naming by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        PresetRow(
            presets = presets,
            speed = speed,
            pitch = pitch,
            reverb = reverb,
            onApply = onApplyPreset,
            onDelete = onDeletePreset,
            onSaveCurrent = { naming = true },
        )

        if (naming) {
            SavePresetDialog(
                onDismiss = { naming = false },
                onConfirm = {
                    onSavePreset(it)
                    naming = false
                },
            )
        }

        EffectSlider(
            label = stringResource(R.string.speed),
            value = speed,
            // Below half speed the stretcher smears badly and above double the
            // track stops being recognisable; both ends are past the useful part.
            scale = RatioScale,
            reading = { "%.2f×".format(it) },
            backdrop = backdrop,
            onChange = onSpeed,
            onReset = { onSpeed(1f) },
        )

        EffectSlider(
            label = stringResource(R.string.pitch),
            value = pitch,
            scale = RatioScale,
            // Semitones read better than a ratio for pitch: "+3" is a musical
            // amount, "1.19×" is not.
            reading = ::formatSemitones,
            backdrop = backdrop,
            onChange = onPitch,
            onReset = { onPitch(1f) },
        )

        val off = stringResource(R.string.off)
        EffectSlider(
            label = stringResource(R.string.reverb),
            value = reverb,
            scale = LinearScale,
            reading = { if (it <= 0f) off else "${(it * 100).roundToInt()}%" },
            backdrop = backdrop,
            onChange = onReverb,
            onReset = { onReverb(0f) },
        )

        SleepTimerRow()
    }
}

/**
 * When to stop, under the things that change how it sounds.
 *
 * Here rather than in the settings because it is about the song playing now,
 * like everything else in this panel, and because it is set from bed. What it
 * sets lives in the service; see SleepTimer.
 */
@Composable
private fun SleepTimerRow() {
    val endsAt by dev.lelonio.square.playback.SleepTimer.endsAt.collectAsStateWithLifecycle()
    val chosen by dev.lelonio.square.playback.SleepTimer.minutes.collectAsStateWithLifecycle()
    val atTrackEnd by dev.lelonio.square.playback.SleepTimer.atTrackEnd.collectAsStateWithLifecycle()

    // Read once a second while something is running, and not at all otherwise:
    // a clock nobody set should cost nothing.
    var left by remember { mutableStateOf(dev.lelonio.square.playback.SleepTimer.remaining()) }
    LaunchedEffect(endsAt) {
        while (endsAt != null) {
            left = dev.lelonio.square.playback.SleepTimer.remaining()
            kotlinx.coroutines.delay(1_000)
        }
        left = null
    }

    Text(
        stringResource(R.string.sleep_timer),
        style = MaterialTheme.typography.labelLarge,
        color = GlassInkDim,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SleepChip(
            label = stringResource(R.string.off),
            selected = endsAt == null && !atTrackEnd,
            onClick = { dev.lelonio.square.playback.SleepTimer.cancel() },
        )
        SleepLengths.forEach { minutes ->
            SleepChip(
                label = stringResource(R.string.sleep_timer_minutes, minutes),
                selected = chosen == minutes,
                onClick = { dev.lelonio.square.playback.SleepTimer.inMinutes(minutes) },
            )
        }
        SleepChip(
            label = stringResource(R.string.sleep_timer_track_end),
            selected = atTrackEnd,
            onClick = { dev.lelonio.square.playback.SleepTimer.atEndOfTrack() },
        )
    }

    // What the chosen chip means, in words and counting down: a lit chip says
    // which length was asked for, not how much of it is left.
    val running = left
    if (atTrackEnd || running != null) {
        Text(
            if (atTrackEnd) {
                stringResource(R.string.sleep_timer_track_end_active)
            } else {
                stringResource(
                    R.string.sleep_timer_left,
                    dev.lelonio.square.ui.library.formatDuration(running ?: 0L),
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = GlassInkDim,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/** A chip of the timer row, drawn like the preset chips above it. */
@Composable
private fun SleepChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        color = if (selected) GlassInk else GlassInkDim,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.background
                },
            )
            .pressable(onClick, pressedScale = 0.94f)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** The lengths offered, which are the ones every player offers. */
private val SleepLengths = listOf(5, 15, 30, 45, 60)

/**
 * The preset chips.
 *
 * A custom preset can be deleted from its own chip once it is the selected one,
 * rather than through a separate edit mode: there are only ever a handful of
 * these, and a mode to manage four chips is more interface than the job needs.
 */
@Composable
private fun PresetRow(
    presets: List<EffectPreset>,
    speed: Float,
    pitch: Float,
    reverb: Float,
    onApply: (EffectPreset) -> Unit,
    onDelete: (String) -> Unit,
    onSaveCurrent: () -> Unit,
) {
    Text(
        stringResource(R.string.presets),
        style = MaterialTheme.typography.labelLarge,
        color = GlassInkDim,
        modifier = Modifier.padding(bottom = 8.dp),
    )

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        presets.forEach { preset ->
            val selected = preset.matches(speed, pitch, reverb)
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.background
                        },
                    )
                    .clickable { onApply(preset) }
                    .padding(start = 14.dp, end = if (selected && !preset.builtIn) 6.dp else 14.dp)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    // "Slowed + Reverb" and "Sped Up" are the names these edits
                    // go by everywhere and are left alone; "Original" is a plain
                    // word and is read from resources.
                    if (preset.id == "original") stringResource(R.string.preset_original)
                    else preset.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    color = if (selected) GlassInk else GlassInkDim,
                )
                if (selected && !preset.builtIn) {
                    Icon(
                        PhosphorIcons.Regular.X,
                        contentDescription = stringResource(R.string.delete_preset, preset.name),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(16.dp)
                            .clip(RoundedCornerShape(50))
                            .clickable { onDelete(preset.id) },
                    )
                }
            }
        }

        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onSaveCurrent)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                PhosphorIcons.Regular.Plus,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                stringResource(R.string.save),
                style = MaterialTheme.typography.bodySmall,
                color = GlassInk,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun SavePresetDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_preset)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.name)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * How a value is laid out along the track.
 *
 * Speed and pitch are ratios, and distance between ratios is multiplicative:
 * half speed and double speed are the same amount of change, one octave either
 * way. Placed linearly, 1.0 lands a third of the way along a 0.5 to 2 track,
 * so normal sits off centre and the two halves of the control mean different
 * amounts. Placing by log2 fixes both at once, and it is also how the ear
 * hears the difference.
 */
private interface SliderScale {
    val positions: ClosedFloatingPointRange<Float>
    fun position(value: Float): Float
    fun value(position: Float): Float
}

/** Symmetric around 1.0: half at one end, double at the other, normal in the middle. */
private object RatioScale : SliderScale {
    override val positions = -1f..1f
    override fun position(value: Float) = (ln(value.toDouble()) / ln(2.0)).toFloat()
    override fun value(position: Float) = 2f.pow(position)
}

/** For a plain amount, where the number already means what it says. */
private object LinearScale : SliderScale {
    override val positions = 0f..1f
    override fun position(value: Float) = value
    override fun value(position: Float) = position
}

@Composable
private fun EffectSlider(
    label: String,
    value: Float,
    scale: SliderScale,
    /**
     * Given the value under the finger, not the one the player has.
     *
     * The two differ for the length of a drag, and reading the settled value
     * meant the number stood still while the thumb moved: the control gave no
     * answer to "what am I choosing?" until it had already been chosen.
     */
    reading: (Float) -> String,
    backdrop: dev.lelonio.square.ui.glass.backdrop.Backdrop,
    onChange: (Float) -> Unit,
    onReset: () -> Unit,
) {
    // What the thumb shows while the player has not caught up yet.
    //
    // The player is told the new value only once the drag settles — writing it
    // every frame starves the audio output — so between the two the slider has
    // to speak for itself, or it springs back under the finger.
    var dragged by remember { mutableStateOf<Float?>(null) }
    val shown = dragged ?: value
    LaunchedEffect(value) {
        if (dragged != null && kotlin.math.abs(value - dragged!!) < 0.0005f) dragged = null
    }

    Column(Modifier.padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = GlassInkDim,
                modifier = Modifier.weight(1f),
            )
            Text(reading(shown), style = MaterialTheme.typography.titleMedium)
            // A one-tap way back to unmodified. Dragging a slider to exactly 1.0
            // is fiddly, and a stuck-off-centre value is the kind of thing that
            // gets mistaken for broken playback.
            Text(
                stringResource(R.string.reset),
                style = MaterialTheme.typography.bodySmall,
                color = GlassInk,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onReset)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        LiquidSlider(
            // The track is in position space, not value space: everything the
            // slider itself does, the thumb, the fill, the animation, is even
            // along it, and the curve lives only in these two conversions.
            value = { scale.position(shown) },
            onValueChange = { position ->
                val v = scale.value(position)
                dragged = v
                onChange(v)
            },
            valueRange = scale.positions,
            // The smallest step worth animating to. Below this the thumb chases
            // values the ear cannot tell apart.
            visibilityThreshold = 0.001f,
            backdrop = backdrop,
            accentColor = MaterialTheme.colorScheme.primary,
            trackColor = GlassInk.copy(alpha = 0.22f),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Pitch ratio as semitones, the unit the control is actually thought in. */
private fun formatSemitones(pitch: Float): String {
    val semitones = 12.0 * (ln(pitch.toDouble()) / ln(2.0))
    return "%+.1f st".format(semitones)
}

@Composable
internal fun EmptyPanel(message: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = GlassInkDim,
        )
    }
}
