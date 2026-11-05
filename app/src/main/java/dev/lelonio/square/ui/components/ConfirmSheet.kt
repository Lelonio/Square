package dev.lelonio.square.ui.components

import androidx.compose.runtime.Composable
import dev.lelonio.square.ui.glass.backdrop.Backdrop

/**
 * One question with two answers: upstream's GlassActionSheet, the answer
 * over the cancel button, in red where it takes something away.
 */
@Composable
fun ConfirmSheet(
    visible: Boolean,
    title: String,
    message: String,
    confirmLabel: String,
    cancelLabel: String,
    @Suppress("UNUSED_PARAMETER") backdrop: Backdrop,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /** Red rather than the ink, for an answer that takes something away. */
    destructive: Boolean = false,
) {
    GlassActionSheet(
        visible = visible,
        onDismiss = onDismiss,
        title = title,
        message = message,
        cancelLabel = cancelLabel,
    ) {
        GlassActionSheetAction(
            label = confirmLabel,
            style = if (destructive) GlassActionSheetStyle.Destructive else GlassActionSheetStyle.Default,
            onClick = onConfirm,
        )
    }
}
