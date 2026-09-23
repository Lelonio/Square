package dev.lelonio.square.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.adamglin.phosphoricons.fill.SquaresFour
import com.adamglin.phosphoricons.fill.MusicNotes
import com.adamglin.phosphoricons.fill.Broadcast
import com.adamglin.phosphoricons.bold.MagnifyingGlass
import com.adamglin.phosphoricons.Bold
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import android.os.Build
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.toArgb
import dev.lelonio.square.ui.theme.softShadow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.regular.Drop
import com.adamglin.phosphoricons.regular.Aperture
import com.adamglin.phosphoricons.regular.Swatches
import com.adamglin.phosphoricons.regular.Highlighter
import com.adamglin.phosphoricons.regular.Sparkle
import com.adamglin.phosphoricons.regular.Waves
import com.adamglin.phosphoricons.regular.MagicWand
import com.adamglin.phosphoricons.regular.Sun
import com.adamglin.phosphoricons.regular.Stack
import com.adamglin.phosphoricons.regular.Rows
import com.adamglin.phosphoricons.regular.DeviceMobile
import com.adamglin.phosphoricons.regular.ArrowsInLineHorizontal
import com.adamglin.phosphoricons.regular.Palette
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.House
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.adamglin.phosphoricons.regular.MusicNotes
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.CaretUp
import com.adamglin.phosphoricons.regular.Check
import dev.lelonio.square.R
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.data.GlassProfile
import dev.lelonio.square.ui.glass.GlassEffectConfig
import dev.lelonio.square.ui.glass.GlassStyle
import dev.lelonio.square.ui.glass.LiquidSlider
import dev.lelonio.square.ui.glass.LiquidToggle
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.layerBackdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import dev.lelonio.square.ui.glass.liquidGlass
import dev.lelonio.square.ui.glass.shapes.ContinuousCapsule
import dev.lelonio.square.ui.glass.LENS_MAX_DP
import dev.lelonio.square.ui.glass.isGlassSupported
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import kotlin.math.roundToInt

/**
 * The glass, and what it costs.
 *
 * A profile at the top and the numbers behind it folded away underneath, which
 * is the shape of the decision: almost nobody wants to choose a refraction
 * amount, and the person who does is not looking for a wizard. Everything here
 * is live — the settings screen is made of the same glass, so a slider moved
 * shows its own answer on the way past.
 */
/**
 * The glass page: the preview above, pinned, and its settings under it.
 *
 * The preview used to scroll away with everything else, which left the numbers
 * being moved with nothing on screen to show what they did — the one thing the
 * preview is for. It is its own page now, and the settings are in the
 * material's own groups rather than one long list behind a "more" row: the
 * list had grown past what a person will read, and what they came for is
 * usually one group of it.
 */
@Composable
fun GlassPage(backdrop: Backdrop, contentPadding: PaddingValues, header: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { (context.applicationContext as SquareApplication).glass }
    val profile by store.profile.collectAsStateWithLifecycle()
    val config by store.config.collectAsStateWithLifecycle()
    val liquid = config.style != GlassStyle.TRANSPARENT

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding()),
    ) {
        header()

        // Phones below Android 12 have no RenderEffect and so no blur at all:
        // they already run the translucent film the fastest profile picks, and
        // offering the other two would be offering something that cannot happen.
        if (!isGlassSupported()) {
            Text(
                stringResource(R.string.glass_unsupported),
                style = MaterialTheme.typography.bodySmall,
                color = InkDim,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            )
            return@Column
        }

        GlassPreview(config = config, backdrop = backdrop)

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        ) {
            item("material") {
                SettingsSection(stringResource(R.string.glass_group_material)) {
                    GlassProfile.entries.forEachIndexed { index, entry ->
                        if (index > 0) SettingsDivider()
                        SettingsChoiceRow(
                            label = stringResource(entry.label),
                            selected = entry == profile,
                        ) { store.setProfile(entry) }
                    }
                    SettingsDivider()
                    // The saturation of what shows through. Blur averages colour
                    // towards grey, and this is what puts it back — at zero the
                    // glass is honest and looks washed out.
                    GlassSlider(
                        label = stringResource(R.string.glass_vibrancy),
                        icon = PhosphorIcons.Regular.Drop,
                        value = config.vibrancy,
                        range = 0f..2f,
                        readout = { "${(it * 50).roundToInt()}%" },
                        backdrop = backdrop,
                        onChange = store::setVibrancy,
                    )
                    // The liquid treatment, which the fastest profile does not
                    // have: no capture is taken, so there is nothing to blur.
                    // Shown as absent rather than as a slider that does nothing.
                    if (liquid) {
                        SettingsDivider()
                        GlassSlider(
                            label = stringResource(R.string.glass_blur),
                        icon = PhosphorIcons.Regular.Aperture,
                            value = config.blurRadius,
                            range = 0f..24f,
                            readout = { "${it.roundToInt()} dp" },
                            backdrop = backdrop,
                            onChange = store::setBlur,
                        )
                    }
                }
            }

            item("colour") {
                SettingsSection(stringResource(R.string.glass_group_colour)) {
                    TintRow(
                        hue = config.tintHue,
                        strength = config.tintStrength,
                        fromSystem = store.tintFromSystem,
                        backdrop = backdrop,
                        onHue = store::setTintHue,
                        onSystem = store::setTintFromSystem,
                        onStrength = store::setTintStrength,
                    )
                    SettingsDivider()
                    GlassSlider(
                        label = stringResource(R.string.glass_film),
                        icon = PhosphorIcons.Regular.Swatches,
                        value = config.surfaceOpacity,
                        range = 0f..1f,
                        readout = { "${(it * 100).roundToInt()}%" },
                        backdrop = backdrop,
                        onChange = store::setSurfaceOpacity,
                    )
                    SettingsDivider()
                    GlassSlider(
                        label = stringResource(R.string.glass_indicator),
                        icon = PhosphorIcons.Regular.Highlighter,
                        value = config.puckOpacity,
                        range = 0f..1f,
                        readout = { "${(it * 100).roundToInt()}%" },
                        backdrop = backdrop,
                        onChange = store::setPuckOpacity,
                    )
                }
            }

            if (liquid) {
                item("edges") {
                    SettingsSection(stringResource(R.string.glass_group_edges)) {
                        GlassSlider(
                            label = stringResource(R.string.glass_rim),
                        icon = PhosphorIcons.Regular.Sparkle,
                            value = config.highlightOpacity,
                            range = 0f..1f,
                            readout = { "${(it * 100).roundToInt()}%" },
                            backdrop = backdrop,
                            onChange = store::setHighlightOpacity,
                        )
                        SettingsDivider()
                        GlassSlider(
                            label = stringResource(R.string.glass_refraction_depth),
                        icon = PhosphorIcons.Regular.Waves,
                            value = config.lensHeight,
                            range = 0f..1f,
                            readout = { "${(it * LENS_MAX_DP).roundToInt()} dp" },
                            backdrop = backdrop,
                            onChange = store::setLensHeight,
                        )
                        SettingsDivider()
                        GlassSlider(
                            label = stringResource(R.string.glass_refraction_amount),
                        icon = PhosphorIcons.Regular.MagicWand,
                            value = config.lensAmount,
                            range = 0f..1f,
                            readout = { "${(it * LENS_MAX_DP).roundToInt()} dp" },
                            backdrop = backdrop,
                            onChange = store::setLensAmount,
                        )
                        SettingsDivider()
                        GlassSwitch(
                            label = stringResource(R.string.glass_dispersion),
                        icon = PhosphorIcons.Regular.Sun,
                            checked = config.chromaticAberration,
                            backdrop = backdrop,
                            onChange = store::setChromaticAberration,
                        )
                        SettingsDivider()
                        GlassSwitch(
                            label = stringResource(R.string.glass_depth),
                        icon = PhosphorIcons.Regular.Stack,
                            checked = config.depthEffect,
                            backdrop = backdrop,
                            onChange = store::setDepthEffect,
                        )
                    }
                }
            }

            // Where the glass is allowed to be: the blunt instrument, kept
            // last. Turning the player's off saves more than any slider above,
            // since it is by far the largest surface in the app.
            item("where") {
                SettingsSection(stringResource(R.string.glass_group_where)) {
                    GlassSwitch(
                        label = stringResource(R.string.glass_where_bar),
                        icon = PhosphorIcons.Regular.Rows,
                        checked = config.navBarEnabled,
                        backdrop = backdrop,
                        onChange = store::setNavBarEnabled,
                    )
                    SettingsDivider()
                    GlassSwitch(
                        label = stringResource(R.string.glass_where_mini_player),
                        icon = PhosphorIcons.Regular.MusicNotes,
                        checked = config.miniPlayerEnabled,
                        backdrop = backdrop,
                        onChange = store::setMiniPlayerEnabled,
                    )
                    SettingsDivider()
                    GlassSwitch(
                        label = stringResource(R.string.glass_where_player),
                        icon = PhosphorIcons.Regular.DeviceMobile,
                        checked = config.playerEnabled,
                        backdrop = backdrop,
                        onChange = store::setPlayerEnabled,
                    )
                }
            }

            item("reset") {
                SettingsSection(null) {
                    Text(
                        stringResource(R.string.glass_reset),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = Ink,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { store.reset() }
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                    )
                }
            }
        }
    }
}


/**
 * A bar that does nothing, so you can see what you are doing.
 *
 * The real one is at the bottom of the screen and the settings page is over it:
 * moving a slider changed a bar nobody could see, and the only way to judge the
 * change was to leave the page that made it. This is the same three shapes made
 * of the same material — the capsule, the selected tab's wash, the circle beside
 * it and the playing pill above — with nothing behind them but the artwork this
 * page is already sitting on, and no behaviour at all.
 */
@Composable
private fun GlassPreview(config: GlassEffectConfig, backdrop: Backdrop) {
    val barConfig = if (config.navBarEnabled) config else config.copy(style = GlassStyle.TRANSPARENT)
    // Its own backdrop, and this is the whole reason the preview works at all.
    //
    // Sampling the page it sits on showed nothing: the settings are dark text on
    // a dark wash, and glass over an even surface is an even surface. Frost,
    // refraction and dispersion are only visible against edges and colour, so
    // the preview brings its own — a band of it, recorded into a layer the
    // shapes below sample instead of the page.
    val stage = rememberLayerBackdrop()
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(20.dp))
                .layerBackdrop(stage)
                .background(
                    Brush.linearGradient(
                        listOf(
                            accent,
                            Color(0xFFFF7A45),
                            Color(0xFF2ED3C6),
                            Color(0xFF1B1B1B),
                        ),
                    ),
                ),
        ) {
            // Hard edges, because a gradient alone bends invisibly: what shows
            // the lens is a straight line going crooked as it passes the rim.
            Column(
                Modifier
                    .matchParentSize()
                    .padding(vertical = 6.dp),
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                repeat(6) { index ->
                    Box(
                        Modifier
                            .fillMaxWidth(if (index % 2 == 0) 0.94f else 0.7f)
                            .height(5.dp)
                            .background(
                                if (index % 2 == 0) {
                                    Color.White.copy(alpha = 0.85f)
                                } else {
                                    Color.Black.copy(alpha = 0.5f)
                                },
                            ),
                    )
                }
            }
        }

    Column(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The playing pill, at the size the real one is.
        Row(
            Modifier
                .fillMaxWidth(0.86f)
                .height(46.dp)
                .liquidGlass(
                    config = if (config.miniPlayerEnabled) config else config.copy(style = GlassStyle.TRANSPARENT),
                    shape = ContinuousCapsule(),
                    highlightAlpha = 0.3f,
                    ownBackdrop = stage,
                )
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Ink.copy(alpha = 0.22f)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth(0.55f)
                        .height(7.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Ink.copy(alpha = 0.55f)),
                )
                Box(
                    Modifier
                        .fillMaxWidth(0.35f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Ink.copy(alpha = 0.28f)),
                )
            }
            Icon(
                PhosphorIcons.Fill.Play,
                contentDescription = null,
                tint = Ink,
                modifier = Modifier.size(18.dp),
            )
        }

        // The bar as it is: five places across one capsule, the first lit,
        // search among them rather than in a circle of its own — the circle
        // only exists once the bar has folded, which this preview does not do.
        Row(
            Modifier
                .padding(top = 10.dp)
                .fillMaxWidth(0.94f)
                .liquidGlass(
                    config = barConfig,
                    shape = ContinuousCapsule(),
                    highlightAlpha = 0.3f,
                    ownBackdrop = stage,
                )
                .padding(vertical = 5.dp, horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PreviewTab(PhosphorIcons.Fill.House, R.string.home, selected = true, config = barConfig)
            PreviewTab(PhosphorIcons.Fill.SquaresFour, R.string.tab_new, selected = false, config = barConfig)
            PreviewTab(PhosphorIcons.Fill.Broadcast, R.string.tab_radio, selected = false, config = barConfig)
            PreviewTab(PhosphorIcons.Fill.MusicNotes, R.string.library, selected = false, config = barConfig)
            PreviewTab(PhosphorIcons.Bold.MagnifyingGlass, R.string.search, selected = false, config = barConfig)
        }
    }
    }
}

/** One tab of the dummy bar, with the selection wash the real puck draws. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.PreviewTab(
    icon: ImageVector,
    @StringRes label: Int,
    selected: Boolean,
    config: GlassEffectConfig,
) {
    val wash = if (config.puckColor.isSpecified) config.puckColor else Ink
    Box(
        Modifier
            .weight(1f, fill = true)
            .height(44.dp)
            .then(
                if (selected) {
                    Modifier
                        .clip(ContinuousCapsule())
                        .background(wash.copy(alpha = config.puckOpacity.coerceIn(0f, 1f)))
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) Ink else InkDim,
                modifier = Modifier.size(19.dp),
            )
            Text(
                stringResource(label),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 7.sp,
                lineHeight = 8.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) Ink else InkDim,
                maxLines = 1,
            )
        }
    }
}

/**
 * The glass's own colour, picked off a strip of every hue there is.
 *
 * A strip rather than a row of swatches: the request was any colour, and a
 * gradient the width of the page holds all of them in the height of one row,
 * where a palette of presets would hold eight and still answer "not that one".
 * The circle on the left is the way back to no colour at all, and the strength
 * below it only appears once there is a colour to weaken; see #29.
 */
@Composable
private fun TintRow(
    hue: Float,
    strength: Float,
    fromSystem: Boolean,
    backdrop: Backdrop,
    onHue: (Float?) -> Unit,
    onSystem: () -> Unit,
    onStrength: (Float) -> Unit,
) {
    val on = hue >= 0f
    Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RowIcon(PhosphorIcons.Regular.Palette)
            Text(
                stringResource(R.string.glass_tint),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(if (on) R.string.glass_tint_clear else R.string.glass_tint_none),
                style = MaterialTheme.typography.bodyMedium,
                color = if (on) MaterialTheme.colorScheme.primary else InkDim,
                modifier = Modifier
                    .clip(ContinuousCapsule())
                    .clickable(enabled = on) { onHue(null) }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        // What the strip shows while a finger is on it.
        //
        // Every write rebuilds the material and redraws every pane of glass on
        // the page, and a drag reports a position per frame: writing each one
        // held the main thread long enough for Android to call the app
        // unresponsive. The thumb follows the finger from here, and the
        // setting is written at most every [TINT_WRITE_MS] and again when the
        // finger lifts.
        var dragHue by remember { mutableStateOf<Float?>(null) }
        var lastWrite by remember { mutableLongStateOf(0L) }
        val shown = dragHue ?: hue
        val hues = remember {
            List(HUE_STOPS) { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 360f / (HUE_STOPS - 1), 0.8f, 1f))) }
        }
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 2.dp)
                .height(30.dp),
        ) {
            val width = constraints.maxWidth.toFloat()
            val pick = { x: Float, settled: Boolean ->
                val picked = (x / width).coerceIn(0f, 1f) * 360f
                dragHue = picked
                val now = android.os.SystemClock.uptimeMillis()
                if (settled || now - lastWrite >= TINT_WRITE_MS) {
                    lastWrite = now
                    onHue(picked)
                }
                if (settled) dragHue = null
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .align(Alignment.CenterStart)
                    .clip(ContinuousCapsule())
                    .background(Brush.horizontalGradient(hues))
                    .pointerInput(width) {
                        detectTapGestures { pick(it.x, true) }
                    }
                    .pointerInput(width) {
                        detectHorizontalDragGestures(
                            onDragEnd = { dragHue?.let { pick(width * (it / 360f), true) } },
                            onDragCancel = { dragHue = null },
                        ) { change, _ -> pick(change.position.x, false) }
                    },
            )
            if (on || dragHue != null) {
                val x = with(LocalDensity.current) { (width * (shown / 360f)).toDp() }
                Box(
                    Modifier
                        .offset(x = x - 11.dp)
                        .size(22.dp)
                        .clip(ContinuousCapsule())
                        .background(Color.White)
                        .padding(3.dp)
                        .clip(ContinuousCapsule())
                        .background(Color(android.graphics.Color.HSVToColor(floatArrayOf(shown, 0.8f, 1f))))
                        .align(Alignment.CenterStart),
                )
            }
        }
        // The handful worth a tap, and the phone's own accent beside them: the
        // strip has every colour, but nobody drags to find plain red.
        Row(
            Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val accent = Color(LocalContext.current.getColor(android.R.color.system_accent1_400))
                Swatch(colour = accent, selected = fromSystem, onClick = onSystem)
            }
            PRESET_HUES.forEach { preset ->
                Swatch(
                    colour = Color(android.graphics.Color.HSVToColor(floatArrayOf(preset, 0.8f, 1f))),
                    selected = !fromSystem && on && kotlin.math.abs(hue - preset) < 1f,
                    onClick = { onHue(preset) },
                )
            }
        }
        if (on) {
            GlassSlider(
                label = stringResource(R.string.glass_tint_strength),
                value = strength,
                range = 0f..1f,
                readout = { "${(it * 100).roundToInt()}%" },
                backdrop = backdrop,
                onChange = onStrength,
                padded = false,
            )
        }
    }
}

/** One colour to tap, lit while it is the one in use. */
@Composable
private fun Swatch(colour: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(26.dp)
            .clip(ContinuousCapsule())
            .background(if (selected) Ink else Color.Transparent)
            .padding(if (selected) 3.dp else 0.dp)
            .clip(ContinuousCapsule())
            .background(colour)
            .clickable(onClick = onClick),
    )
}

/**
 * How often a drag along the strip is allowed to write the setting.
 *
 * Every write rebuilds the material, and this page is made of it: a dozen
 * panes, each re-applying a chain of shaders. At sixteen writes a second the
 * main thread never caught up and Android called the app unresponsive, twice.
 * The strip's own thumb follows the finger at full speed from state that costs
 * nothing; this is only how often the rest of the app is told.
 */
private const val TINT_WRITE_MS = 250L

/** Stops in the hue strip: enough that the gradient has no visible banding. */
private const val HUE_STOPS = 13

/** Red, orange, yellow, green, cyan, blue, violet, magenta. */
private val PRESET_HUES = listOf(0f, 32f, 52f, 130f, 186f, 220f, 268f, 310f)

/**
 * One number, with what it currently is.
 *
 * The readout is in the unit the number means rather than the 0..1 it is stored
 * as: "19 dp" is something you can see on the screen in front of you, and "0.4"
 * is a number about a number.
 */
@Composable
private fun GlassSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    readout: (Float) -> String,
    backdrop: Backdrop,
    icon: ImageVector? = null,
    onChange: (Float) -> Unit,
    /** False where the caller has already indented it; see TintRow. */
    padded: Boolean = true,
) {
    Column(
        if (padded) Modifier.padding(horizontal = 18.dp, vertical = 8.dp) else Modifier.padding(top = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            icon?.let { RowIcon(it) }
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                readout(value),
                style = MaterialTheme.typography.bodyMedium,
                color = InkDim,
            )
        }
        // Drawn rather than made of glass, which every row of this page used
        // to be. Each liquid slider and toggle records a backdrop layer of its
        // own, and a page with a dozen of them on screen rebuilds a dozen
        // layers per frame: scrolling it held the main thread long enough for
        // Android to call the app unresponsive. The real material is above, in
        // the preview, where one surface shows what all these numbers do.
        PlainSlider(
            value = value,
            range = range,
            onChange = onChange,
            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
        )
    }
}

@Composable
private fun GlassSwitch(
    label: String,
    checked: Boolean,
    backdrop: Backdrop,
    icon: ImageVector? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(start = 18.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.let { RowIcon(it) }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
        PlainToggle(checked = checked, onChange = onChange)
    }
}

/**
 * The settings page's own row shapes, shared with this file.
 *
 * Copies rather than the originals only because those are private to
 * [SettingsScreen]; if a third file ever needs them they should move.
 */
@Composable
internal fun SettingsSection(title: String?, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
        if (title != null) Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = InkDim,
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Ink.copy(alpha = 0.07f)),
            content = { content() },
        )
    }
}

@Composable
internal fun SettingsDivider() {
    androidx.compose.material3.HorizontalDivider(
        color = Ink.copy(alpha = 0.06f),
        // Indented to where the words start, not to the card's edge: a line
        // that runs under the icons cuts the column of them in half.
        modifier = Modifier.padding(start = ROW_TEXT_START, end = 18.dp),
    )
}

/**
 * The mark at the head of a row.
 *
 * Every row used to be the same: a word on the left and a number on the right,
 * a dozen of them stacked, and finding the one you came for meant reading all
 * of them. A glyph in a tinted tile gives each row something to recognise it
 * by before the word is read, and gives the column a rhythm.
 */
@Composable
internal fun RowIcon(icon: ImageVector) {
    val accent = settingsAccent()
    Box(
        Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(17.dp))
    }
}

/** Where a row's words begin, icon and its gap included. */
internal val ROW_TEXT_START = 60.dp

/**
 * What the settings are marked in: the glass's colour where it has been given
 * one, and the app's own accent otherwise.
 *
 * Somebody who has just made every pane of the app cyan does not want the page
 * that did it marked in green; the tiles and the ticks follow the choice.
 */
@Composable
internal fun settingsAccent(): Color = LocalSettingsAccent.current ?: MaterialTheme.colorScheme.primary

/**
 * The accent for one whole page, worked out once.
 *
 * Every mark and every control used to read the glass settings for itself, and
 * each read is a collector on a flow, made and thrown away as the list
 * recycles its rows. A page has one answer; it is provided here.
 */
internal val LocalSettingsAccent = androidx.compose.runtime.compositionLocalOf<Color?> { null }

/** The colour that local carries, worked out from the glass and the theme. */
@Composable
internal fun rememberSettingsAccent(): Color {
    val context = LocalContext.current
    val store = remember(context) { (context.applicationContext as SquareApplication).glass }
    val config by store.config.collectAsStateWithLifecycle()
    val accent = MaterialTheme.colorScheme.primary
    if (config.tintHue < 0f) return accent
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(accent.toArgb(), hsv)
    val strength = config.tintStrength.coerceIn(0f, 1f)
    val saturation = (hsv[1] + (ACCENT_SATURATION - hsv[1]) * strength).coerceIn(0f, 1f)
    return Color(
        android.graphics.Color.HSVToColor(
            floatArrayOf(config.tintHue, saturation, hsv[2].coerceAtLeast(ACCENT_MIN_VALUE)),
        ),
    )
}

/** How coloured a tinted mark goes, and how dark it is allowed to be. */
private const val ACCENT_SATURATION = 0.7f
private const val ACCENT_MIN_VALUE = 0.75f

@Composable
internal fun SettingsChoiceRow(
    label: String,
    selected: Boolean,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.let { RowIcon(it) }
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) Ink else InkDim,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                PhosphorIcons.Regular.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * A slider that costs nothing to draw: a track, a fill and a knob.
 *
 * See the note where this replaced the liquid one — a settings list is a dozen
 * controls at once, and the glass ones each record a layer of the screen.
 */
@Composable
internal fun PlainSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = settingsAccent()
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    BoxWithConstraints(modifier.fillMaxWidth().height(28.dp)) {
        val width = constraints.maxWidth.toFloat()
        val fraction = ((value - range.start) / span).coerceIn(0f, 1f)
        val pick = { x: Float -> onChange(range.start + (x / width).coerceIn(0f, 1f) * span) }
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .align(Alignment.CenterStart)
                .clip(ContinuousCapsule())
                .background(Ink.copy(alpha = 0.22f)),
        )
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(6.dp)
                .align(Alignment.CenterStart)
                .clip(ContinuousCapsule())
                .background(accent),
        )
        Box(
            Modifier
                .offset(x = with(LocalDensity.current) { (width * fraction).toDp() } - 11.dp)
                .size(22.dp)
                .align(Alignment.CenterStart)
                .softShadow(ContinuousCapsule(), elevation = 6.dp)
                .clip(ContinuousCapsule())
                .background(Color.White),
        )
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(width, range) { detectTapGestures { pick(it.x) } }
                .pointerInput(width, range) {
                    detectHorizontalDragGestures { change, _ -> pick(change.position.x) }
                },
        )
    }
}

/** The same trade as [PlainSlider], for a switch. */
@Composable
internal fun PlainToggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    val accent = settingsAccent()
    val knob by androidx.compose.animation.core.animateDpAsState(
        if (checked) 22.dp else 2.dp,
        label = "toggleKnob",
    )
    Box(
        Modifier
            .size(width = 46.dp, height = 28.dp)
            .clip(ContinuousCapsule())
            .background(if (checked) accent else Ink.copy(alpha = 0.22f))
            .clickable { onChange(!checked) },
    ) {
        Box(
            Modifier
                .offset(x = knob)
                .align(Alignment.CenterStart)
                .size(24.dp)
                .softShadow(ContinuousCapsule(), elevation = 4.dp)
                .clip(ContinuousCapsule())
                .background(Color.White),
        )
    }
}
