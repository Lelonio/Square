package dev.lelonio.square.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * A question with one answer that takes something away: upstream's
 * GlassActionSheet, the answer in red over the cancel button.
 *
 * Drawn for [target] while there is one, and for the last one while the
 * sheet leaves, so it slides away rather than vanishing.
 *
 * The cancel button is the one under the thumb, and the destructive answer
 * the one that has to be reached for: deleting a playlist cannot be taken
 * back.
 */
@Composable
fun <T : Any> ConfirmDialog(
    target: T?,
    title: @Composable (T) -> String,
    message: @Composable (T) -> String,
    confirmLabel: String,
    onConfirm: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    var last by remember { mutableStateOf(target) }
    if (target != null && target != last) last = target
    val shown = target ?: last
    GlassActionSheet(
        visible = target != null,
        onDismiss = onDismiss,
        title = shown?.let { title(it) },
        message = shown?.let { message(it) },
    ) {
        GlassActionSheetAction(
            label = confirmLabel,
            style = GlassActionSheetStyle.Destructive,
            onClick = { target?.let(onConfirm) },
        )
    }
}
