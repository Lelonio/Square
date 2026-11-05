package dev.lelonio.square.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.lelonio.square.ui.glass.backdrop.Backdrop

/**
 * What can be done with one track, or one record: upstream's
 * GlassActionSheet, titled with its name and what it is by.
 *
 * Must be drawn above the tab bar and the now-playing bar — that is, at app
 * level and not inside a screen, since those are drawn over the whole
 * navigation host.
 */
@Composable
fun BoxScope.TrackSheet(
    visible: Boolean,
    title: String,
    subtitle: String,
    @Suppress("UNUSED_PARAMETER") artworkUrl: String?,
    @Suppress("UNUSED_PARAMETER") backdrop: Backdrop,
    onDismiss: () -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
) {
    GlassActionSheet(
        visible = visible,
        onDismiss = onDismiss,
        title = title.ifBlank { null },
        message = subtitle.ifBlank { null },
        actions = actions,
    )
}

@Composable
fun TrackSheetAction(
    label: String,
    icon: ImageVector,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    GlassActionSheetAction(
        label = label,
        onClick = onClick,
        icon = icon,
        style = if (destructive) GlassActionSheetStyle.Destructive else GlassActionSheetStyle.Default,
    )
}
