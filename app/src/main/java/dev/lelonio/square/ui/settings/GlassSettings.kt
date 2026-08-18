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
@Composable
fun GlassSection(backdrop: Backdrop) {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as SquareApplication).glass
    }
    val profile by store.profile.collectAsStateWithLifecycle()
    val config by store.config.collectAsStateWithLifecycle()
    val folds by store.barFolds.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }

    SettingsSection(stringResource(R.string.glass)) {
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
            return@SettingsSection
        }

        GlassProfile.entries.forEachIndexed { index, entry ->
            if (index > 0) SettingsDivider()
            SettingsChoiceRow(
                label = stringResource(entry.label),
                selected = entry == profile,
            ) { store.setProfile(entry) }
        }
        SettingsDivider()
        Text(
            stringResource(profile.note),
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        )

        SettingsDivider()
        GlassPreview(config = config, backdrop = backdrop)

        SettingsDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.glass_advanced),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) PhosphorIcons.Regular.CaretUp else PhosphorIcons.Regular.CaretDown,
                contentDescription = null,
                tint = InkDim,
                modifier = Modifier.size(18.dp),
            )
        }

        AnimatedVisibility(expanded) {
            Column {
                SettingsDivider()

                // The saturation of what shows through. Blur averages colour
                // towards grey, and this is what puts it back — at zero the
                // glass is honest and looks washed out.
                GlassSlider(
                    label = stringResource(R.string.glass_vibrancy),
                    value = config.vibrancy,
                    range = 0f..2f,
                    readout = { "${(it * 50).roundToInt()}%" },
                    backdrop = backdrop,
                    onChange = store::setVibrancy,
                )

                // Everything below is the liquid treatment, and the fastest
                // profile does not have one: no capture is taken, so there is
                // nothing to blur or bend. Shown as absent rather than as
                // sliders that do nothing.
                if (config.style != GlassStyle.TRANSPARENT) {
                    SettingsDivider()
                    GlassSlider(
                        label = stringResource(R.string.glass_blur),
                        value = config.blurRadius,
                        range = 0f..24f,
                        readout = { "${it.roundToInt()} dp" },
                        backdrop = backdrop,
                    onChange = store::setBlur,
                    )
                    SettingsDivider()
                    GlassSlider(
                        label = stringResource(R.string.glass_refraction_depth),
                        value = config.lensHeight,
                        range = 0f..1f,
                        readout = { "${(it * LENS_MAX_DP).roundToInt()} dp" },
                        backdrop = backdrop,
                    onChange = store::setLensHeight,
                    )
                    SettingsDivider()
                    GlassSlider(
                        label = stringResource(R.string.glass_refraction_amount),
                        value = config.lensAmount,
                        range = 0f..1f,
                        readout = { "${(it * LENS_MAX_DP).roundToInt()} dp" },
                        backdrop = backdrop,
                    onChange = store::setLensAmount,
                    )
                    SettingsDivider()
                    GlassSlider(
                        label = stringResource(R.string.glass_rim),
                        value = config.highlightOpacity,
                        range = 0f..1f,
                        readout = { "${(it * 100).roundToInt()}%" },
                        backdrop = backdrop,
                    onChange = store::setHighlightOpacity,
                    )
                }

                SettingsDivider()
                GlassSlider(
                    label = stringResource(R.string.glass_indicator),
                    value = config.puckOpacity,
                    range = 0f..1f,
                    readout = { "${(it * 100).roundToInt()}%" },
                    backdrop = backdrop,
                    onChange = store::setPuckOpacity,
                )

                SettingsDivider()
                GlassSlider(
                    label = stringResource(R.string.glass_film),
                    value = config.surfaceOpacity,
                    range = 0f..1f,
                    readout = { "${(it * 100).roundToInt()}%" },
                    backdrop = backdrop,
                    onChange = store::setSurfaceOpacity,
                )

                if (config.style != GlassStyle.TRANSPARENT) {
                    SettingsDivider()
                    GlassSwitch(
                        label = stringResource(R.string.glass_dispersion),
                        note = stringResource(R.string.glass_dispersion_note),
                        checked = config.chromaticAberration,
                    backdrop = backdrop,
                    onChange = store::setChromaticAberration,
                    )
                    SettingsDivider()
                    GlassSwitch(
                        label = stringResource(R.string.glass_depth),
                        note = stringResource(R.string.glass_depth_note),
                        checked = config.depthEffect,
                    backdrop = backdrop,
                    onChange = store::setDepthEffect,
                    )
                }

                // Where the glass is allowed to be. Kept last because it is the
                // blunt instrument: turning the player's off is a bigger saving
                // than any slider above, since it is by far the largest surface
                // in the app.
                SettingsDivider()
                GlassSwitch(
                    label = stringResource(R.string.glass_where_bar),
                    note = null,
                    checked = config.navBarEnabled,
                    backdrop = backdrop,
                    onChange = store::setNavBarEnabled,
                )
                SettingsDivider()
                GlassSwitch(
                    label = stringResource(R.string.glass_where_mini_player),
                    note = null,
                    checked = config.miniPlayerEnabled,
                    backdrop = backdrop,
                    onChange = store::setMiniPlayerEnabled,
                )
                SettingsDivider()
                GlassSwitch(
                    label = stringResource(R.string.glass_where_player),
                    note = null,
                    checked = config.playerEnabled,
                    backdrop = backdrop,
                    onChange = store::setPlayerEnabled,
                )


                SettingsDivider()
                GlassSwitch(
                    label = stringResource(R.string.bar_folds),
                    note = stringResource(R.string.bar_folds_note),
                    checked = folds,
                    backdrop = backdrop,
                    onChange = store::setBarFolds,
                )

                SettingsDivider()
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

        Row(
            Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .liquidGlass(
                        config = barConfig,
                        shape = ContinuousCapsule(),
                        highlightAlpha = 0.3f,
                        ownBackdrop = stage,
                    )
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PreviewTab(PhosphorIcons.Fill.House, selected = true, config = barConfig)
                PreviewTab(PhosphorIcons.Regular.MusicNotes, selected = false, config = barConfig)
            }
            Box(
                Modifier
                    .size(48.dp)
                    .liquidGlass(
                        config = barConfig,
                        shape = ContinuousCapsule(),
                        highlightAlpha = 0.3f,
                        ownBackdrop = stage,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    PhosphorIcons.Regular.MagnifyingGlass,
                    contentDescription = null,
                    tint = Ink,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
    }
}

/** One tab of the dummy bar, with the selection wash the real puck draws. */
@Composable
private fun PreviewTab(icon: ImageVector, selected: Boolean, config: GlassEffectConfig) {
    val wash = if (config.puckColor.isSpecified) config.puckColor else Ink
    Box(
        Modifier
            .size(width = 62.dp, height = 40.dp)
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
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) Ink else InkDim,
            modifier = Modifier.size(22.dp),
        )
    }
}

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
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
        // The app's own slider rather than Material's, which is the argument
        // this whole page is about: a screen for tuning glass, made of chrome
        // that is not glass, is showing you somebody else's material while you
        // adjust yours.
        LiquidSlider(
            value = { value },
            onValueChange = onChange,
            valueRange = range,
            // The smallest step worth animating the thumb to.
            visibilityThreshold = 0.001f,
            backdrop = backdrop,
            accentColor = MaterialTheme.colorScheme.primary,
            trackColor = Ink.copy(alpha = 0.22f),
            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
        )
    }
}

@Composable
private fun GlassSwitch(
    label: String,
    note: String?,
    checked: Boolean,
    backdrop: Backdrop,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (note != null) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = InkDim)
            }
        }
        LiquidToggle(
            selected = { checked },
            onSelect = onChange,
            backdrop = backdrop,
            accent = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * The settings page's own row shapes, shared with this file.
 *
 * Copies rather than the originals only because those are private to
 * [SettingsScreen]; if a third file ever needs them they should move.
 */
@Composable
internal fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(
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
        modifier = Modifier.padding(horizontal = 18.dp),
    )
}

@Composable
internal fun SettingsChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
