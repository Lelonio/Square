package dev.lelonio.square.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.lelonio.square.R
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim

/**
 * Says that a newer build exists, and offers to fetch it.
 *
 * An install that no store can reach only learns about a release if something
 * says so, and the settings row that used to be the only way to find out is a
 * place nobody visits to check. Shown once per release: dismissing it records
 * the version, so the same news is never delivered twice.
 */
@Composable
fun UpdateDialog(
    version: String,
    /** Download size, already formatted, or null when the release did not say. */
    size: String?,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(DialogFill)
                .padding(22.dp),
        ) {
            Text(
                stringResource(R.string.update_available, version),
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
            )
            Text(
                if (size != null) {
                    stringResource(R.string.update_prompt_size, size)
                } else {
                    stringResource(R.string.update_prompt)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = InkDim,
                modifier = Modifier.padding(top = 6.dp),
            )

            Row(
                Modifier
                    .padding(top = 20.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                DialogAction(stringResource(R.string.update_later), InkDim, onDismiss)
                DialogAction(stringResource(R.string.update_install), Ink, onInstall)
            }
        }
    }
}

@Composable
private fun DialogAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Opaque, like the other dialogs: nothing behind this is worth refracting. */
private val DialogFill = Color(0xFF1B1B1B)
