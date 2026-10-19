package dev.lelonio.square.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowCounterClockwise
import com.adamglin.phosphoricons.regular.Bluetooth
import com.adamglin.phosphoricons.regular.DeviceMobileSpeaker
import com.adamglin.phosphoricons.regular.Headphones
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.SpeakerHifi
import com.adamglin.phosphoricons.regular.Usb
import com.adamglin.phosphoricons.regular.X
import dev.lelonio.square.R
import dev.lelonio.square.playback.AudioRoute
import dev.lelonio.square.playback.BuiltInEqPresets
import dev.lelonio.square.playback.Equalizer
import dev.lelonio.square.ui.components.NameDialog
import dev.lelonio.square.ui.glass.shapes.ContinuousCapsule
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import kotlin.math.roundToInt

/**
 * The equaliser: which output, its curve, and the presets to start from.
 *
 * The output comes first because it is what the rest belongs to. The curve
 * shown is the one for whatever is playing through now; the others the app has
 * seen are listed under it, and choosing one edits that one's curve without
 * having to plug it in.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqualizerPage(contentPadding: PaddingValues, header: @Composable () -> Unit) {
    val route by AudioRoute.current.collectAsStateWithLifecycle()
    val curves by Equalizer.curves.collectAsStateWithLifecycle()
    val names by Equalizer.names.collectAsStateWithLifecycle()
    val custom by Equalizer.presets.collectAsStateWithLifecycle()

    // Null follows the output in use; a key is one chosen from the list.
    var picked by remember { mutableStateOf<String?>(null) }
    val editing = picked ?: route.key
    val curve = curves[editing] ?: Equalizer.Curve()
    var saving by remember { mutableStateOf(false) }

    val known = remember(curves, names, route) {
        (listOf(route.key) + names.keys + curves.keys).distinct()
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        item("top") { header() }

        item("device") {
            SettingsSection(null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RowIcon(iconFor(editing))
                    Column(Modifier.weight(1f)) {
                        Text(
                            outputName(editing, names),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                        )
                        Text(
                            stringResource(
                                if (editing == route.key) R.string.eq_in_use else R.string.eq_not_connected,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = InkDim,
                        )
                    }
                    PlainToggle(checked = curve.enabled) { Equalizer.setEnabled(editing, it) }
                }
            }
        }

        item("curve") {
            SettingsSection(stringResource(R.string.eq_curve)) {
                CurveEditor(
                    gains = curve.gains,
                    enabled = curve.enabled,
                    onGain = { band, db -> Equalizer.setGain(editing, band, db) },
                )
                SettingsDivider()
                Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.eq_preamp),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            decibels(curve.preamp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = InkDim,
                        )
                    }
                    PlainSlider(
                        value = curve.preamp,
                        range = -Equalizer.RANGE_DB..Equalizer.RANGE_DB,
                        onChange = { Equalizer.setPreamp(editing, it) },
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    )
                }
                SettingsDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { Equalizer.reset(editing) }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RowIcon(PhosphorIcons.Regular.ArrowCounterClockwise)
                    Text(stringResource(R.string.eq_reset), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        item("presets") {
            SettingsSection(stringResource(R.string.eq_presets)) {
                FlowRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BuiltInEqPresets.forEach { (name, gains) ->
                        PresetChip(
                            label = stringResource(name),
                            selected = curve.enabled && curve.matches(gains),
                        ) { Equalizer.applyPreset(editing, gains) }
                    }
                    custom.forEach { preset ->
                        PresetChip(
                            label = preset.name,
                            selected = curve.enabled && curve.matches(preset.gains),
                            onDelete = { Equalizer.deletePreset(preset.id) },
                        ) { Equalizer.applyPreset(editing, preset.gains) }
                    }
                    PresetChip(
                        label = stringResource(R.string.eq_save_preset),
                        selected = false,
                        icon = PhosphorIcons.Regular.Plus,
                    ) { saving = true }
                }
            }
        }

        if (known.size > 1) item("devices") {
            SettingsSection(stringResource(R.string.eq_devices)) {
                known.forEachIndexed { index, key ->
                    if (index > 0) SettingsDivider()
                    SettingsChoiceRow(
                        label = outputName(key, names) +
                            if (key == route.key) " · " + stringResource(R.string.eq_in_use) else "",
                        selected = key == editing,
                        icon = iconFor(key),
                    ) { picked = key.takeUnless { it == route.key } }
                }
            }
        }

        item("note") {
            Text(
                stringResource(R.string.eq_note),
                style = MaterialTheme.typography.bodySmall,
                color = InkDim,
                modifier = Modifier.padding(horizontal = 26.dp, vertical = 6.dp),
            )
        }
    }

    if (saving) {
        NameDialog(
            title = stringResource(R.string.eq_save_preset),
            initial = "",
            confirmLabel = stringResource(R.string.save),
            onConfirm = { name ->
                if (name.isNotBlank()) Equalizer.savePreset(name, curve.gains)
                saving = false
            },
            onDismiss = { saving = false },
        )
    }
}

/**
 * The ten bands as ten sliders, with the curve they make drawn through them.
 *
 * Each band is its own upright slider, as on a mixing desk: the track the whole
 * height of the range, the bar filled up to where the band is set, and the
 * number above it. The curve runs through the ten knobs, so the shape of the
 * sound is read at a glance and each band's own height can still be told
 * exactly. A finger chooses the band it lands on and then only moves that one,
 * so a drag that drifts sideways does not start dragging the neighbours.
 */
@Composable
private fun CurveEditor(
    gains: List<Float>,
    enabled: Boolean,
    onGain: (Int, Float) -> Unit,
) {
    val accent = settingsAccent()
    val ink = Ink
    val bands = Equalizer.FREQUENCIES.size
    var active by remember { mutableStateOf<Int?>(null) }
    val latestOnGain by rememberUpdatedState(onGain)

    Column(
        Modifier
            .padding(horizontal = 8.dp, vertical = 12.dp)
            .alpha(if (enabled) 1f else 0.45f),
    ) {
        Row(Modifier.fillMaxWidth()) {
            gains.forEachIndexed { band, gain ->
                Text(
                    String.format(java.util.Locale.ROOT, "%.1f", gain),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (band == active) accent else InkDim,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .padding(vertical = 6.dp)
                .pointerInput(bands) {
                    val pad = KNOB_RADIUS.toPx()
                    fun dbAt(y: Float): Float {
                        val span = size.height - pad * 2
                        return (Equalizer.RANGE_DB - (y - pad) / span * Equalizer.RANGE_DB * 2)
                            .coerceIn(-Equalizer.RANGE_DB, Equalizer.RANGE_DB)
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val band = (down.position.x / size.width * bands).toInt().coerceIn(0, bands - 1)
                        active = band
                        down.consume()
                        latestOnGain(band, dbAt(down.position.y))
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            latestOnGain(band, dbAt(change.position.y))
                        }
                        active = null
                    }
                },
        ) {
            val pad = KNOB_RADIUS.toPx()
            val span = size.height - pad * 2
            val y = { db: Float -> pad + (Equalizer.RANGE_DB - db) / (Equalizer.RANGE_DB * 2) * span }
            val column = size.width / bands
            val x = { band: Int -> column * (band + 0.5f) }
            val track = 4.dp.toPx()

            // The middle of the range, faintly: where a band does nothing.
            drawLine(
                ink.copy(alpha = 0.10f),
                Offset(0f, y(0f)),
                Offset(size.width, y(0f)),
                strokeWidth = 1.dp.toPx(),
            )

            // Each slider's whole travel, and a mark at its top.
            for (band in 0 until bands) {
                drawLine(
                    accent.copy(alpha = 0.16f),
                    Offset(x(band), pad),
                    Offset(x(band), size.height),
                    strokeWidth = track,
                    cap = StrokeCap.Round,
                )
                drawCircle(ink.copy(alpha = 0.55f), 2.dp.toPx(), Offset(x(band), pad))
            }

            // The curve through the knobs, carried on flat to either edge.
            // Catmull-Rom, so it passes through every knob and bends smoothly
            // between them rather than in straight lines.
            val points = (0 until bands).map { Offset(x(it), y(gains[it])) }
            val all = listOf(Offset(0f, points.first().y)) + points + Offset(size.width, points.last().y)
            val curve = Path().apply {
                moveTo(all[0].x, all[0].y)
                for (i in 0 until all.size - 1) {
                    val p0 = all[maxOf(i - 1, 0)]
                    val p1 = all[i]
                    val p2 = all[i + 1]
                    val p3 = all[minOf(i + 2, all.size - 1)]
                    cubicTo(
                        p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
                        p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
                        p2.x, p2.y,
                    )
                }
            }
            val fill = Path().apply {
                addPath(curve)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            clipRect {
                drawPath(fill, accent.copy(alpha = 0.16f))
                drawPath(curve, accent, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            }

            // Each band's own height: the bar up to its knob.
            for (band in 0 until bands) {
                val centre = points[band]
                drawLine(
                    accent,
                    Offset(centre.x, size.height),
                    centre,
                    strokeWidth = track,
                    cap = StrokeCap.Round,
                )
                val radius = KNOB_RADIUS.toPx() * if (band == active) 1.25f else 1f
                drawCircle(accent, radius, centre)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Equalizer.FREQUENCIES.forEach { frequency ->
                Text(
                    frequencyLabel(frequency),
                    style = MaterialTheme.typography.labelMedium,
                    color = InkDim,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            stringResource(R.string.eq_hint),
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
    }
}

@Composable
private fun PresetChip(
    label: String,
    selected: Boolean,
    icon: ImageVector? = null,
    onDelete: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val accent = settingsAccent()
    Row(
        Modifier
            .clip(ContinuousCapsule())
            .background(if (selected) accent else Ink.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = if (onDelete != null) 6.dp else 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val ink = if (selected) Color.White else Ink
        icon?.let { Icon(it, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp)) }
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink)
        if (onDelete != null) {
            Icon(
                PhosphorIcons.Regular.X,
                contentDescription = stringResource(R.string.delete),
                tint = ink.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(22.dp)
                    .clip(ContinuousCapsule())
                    .clickable(onClick = onDelete)
                    .padding(4.dp),
            )
        }
    }
}

/** What an output is called on screen. */
@Composable
private fun outputName(key: String, names: Map<String, String>): String {
    names[key]?.takeIf { it.isNotBlank() }?.let { return it }
    return stringResource(
        when (AudioRoute.kindOf(key)) {
            AudioRoute.Kind.SPEAKER -> R.string.eq_speaker
            AudioRoute.Kind.WIRED -> R.string.eq_wired
            AudioRoute.Kind.BLUETOOTH -> R.string.eq_bluetooth
            AudioRoute.Kind.USB -> R.string.eq_usb
            AudioRoute.Kind.OTHER -> R.string.eq_other
        },
    )
}

private fun iconFor(key: String): ImageVector = when (AudioRoute.kindOf(key)) {
    AudioRoute.Kind.SPEAKER -> PhosphorIcons.Regular.DeviceMobileSpeaker
    AudioRoute.Kind.WIRED -> PhosphorIcons.Regular.Headphones
    AudioRoute.Kind.BLUETOOTH -> PhosphorIcons.Regular.Bluetooth
    AudioRoute.Kind.USB -> PhosphorIcons.Regular.Usb
    AudioRoute.Kind.OTHER -> PhosphorIcons.Regular.SpeakerHifi
}

private fun frequencyLabel(hz: Int): String = if (hz >= 1000) "${hz / 1000}k" else "$hz"

private fun decibels(db: Float): String {
    val rounded = (db * 2f).roundToInt() / 2f
    val text = if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
    return (if (rounded > 0f) "+" else "") + text + " dB"
}

private val KNOB_RADIUS = 10.dp
