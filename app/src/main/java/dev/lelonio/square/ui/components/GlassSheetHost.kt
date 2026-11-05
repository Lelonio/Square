package dev.lelonio.square.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.lelonio.square.ui.glass.backdrop.Backdrop

/**
 * Where the app's modal sheets are drawn: over everything, the tab bar, the
 * now-playing bar and the player included, from wherever they are asked for.
 *
 * Upstream's sheets are routes, over the whole app; a sheet drawn inside the
 * page that opened it sat under the bars. And of glass, so drawn in the app's
 * own window, over the layer of everything they cover ([backdrop]), which a
 * popup's window of its own could not read.
 */
@Stable
class GlassSheetHost {
    internal val entries = mutableStateListOf<Entry>()

    /** The layer the sheets refract: the screens, the bars and the player. */
    var backdrop: Backdrop? by mutableStateOf(null)

    /** Whether any sheet is showing or leaving, so the layer is recorded. */
    val open: Boolean get() = entries.isNotEmpty()

    internal class Entry(val content: androidx.compose.runtime.State<@Composable () -> Unit>)

    /** Draws the sheets, in the order they were opened. */
    @Composable
    fun Sheets() {
        entries.forEach { entry -> key(entry) { entry.content.value() } }
    }
}

val LocalGlassSheetHost = staticCompositionLocalOf<GlassSheetHost?> { null }

/**
 * [content] drawn in the app's [GlassSheetHost] while [active]; in place
 * where there is no host.
 */
@Composable
internal fun HostedSheet(active: Boolean, content: @Composable () -> Unit) {
    val host = LocalGlassSheetHost.current
    if (host == null) {
        if (active) content()
        return
    }
    val latest = rememberUpdatedState(content)
    if (!active) return
    DisposableEffect(host) {
        val entry = GlassSheetHost.Entry(latest)
        host.entries += entry
        onDispose { host.entries -= entry }
    }
}
