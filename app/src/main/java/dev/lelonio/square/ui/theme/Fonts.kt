package dev.lelonio.square.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.lelonio.square.R

/** The weights the app sets; anything else is interpolated between them. */
private val WEIGHTS = listOf(300, 400, 500, 600, 700, 800, 900)

/**
 * The app's own face.
 *
 * Inter rather than the face the reference uses. Apple's is SF Pro, and its
 * licence covers interfaces on Apple's platforms — putting the file inside an
 * Android package is redistribution, which that licence does not grant, whatever
 * the app is imitating. Inter was drawn for screens against the same brief and
 * shares what makes the reference's text look the way it does: an upright
 * grotesque with a tall x-height, open apertures and flat terminals. At the same
 * sizes and tracking it is very close, and it is ours to ship.
 *
 * One variable file rather than seven static ones: it weighs less than three of
 * them, and every weight in between is available — which the display sizes here
 * want, since a name set at 46sp needs a different weight from the same name at
 * 22.
 */
@OptIn(ExperimentalTextApi::class)
val Inter = FontFamily(
    WEIGHTS.map { weight ->
        Font(
            R.font.inter,
            weight = FontWeight(weight),
            variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
        )
    },
)

/**
 * The face an artist's name is set in on their page, where the catalogue has
 * no lettering of their own for it.
 *
 * A serif drawn for display sizes, so the name reads as the page's headline
 * rather than as the app's interface shouted larger. Instrument Serif, under
 * the SIL Open Font License; see assets/licenses/InstrumentSerif-OFL.txt.
 */
val InstrumentSerif = FontFamily(Font(R.font.instrument_serif, weight = FontWeight.Normal))

/**
 * [content] with the system font scale held at [max] at most.
 *
 * For chrome of a fixed height: the tab bar and the mini player keep their size
 * whatever the font setting, so text there that grew with it was cut off. Like
 * iOS's tab bar, it follows the setting a little way and then stops.
 */
@androidx.compose.runtime.Composable
fun CappedFontScale(max: Float = 1.15f, content: @androidx.compose.runtime.Composable () -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    if (density.fontScale <= max) {
        content()
    } else {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(density.density, max),
            content = content,
        )
    }
}
