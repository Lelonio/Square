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
 * Asks before something that cannot be taken back.
 *
 * Deleting a playlist is one tap in a menu and Spotify keeps no copy: the list
 * is gone from every client the account has, and there is nowhere in this app or
 * in theirs to get it back. That is the whole argument for the extra tap, and
 * why the destructive answer is the one that has to be reached for rather than
 * the one under the thumb.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(DialogFill)
                .padding(22.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Ink)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = InkDim,
                modifier = Modifier.padding(top = 10.dp),
            )

            Row(
                Modifier
                    .padding(top = 20.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                DialogAction(stringResource(R.string.cancel), InkDim, onDismiss)
                DialogAction(confirmLabel, MaterialTheme.colorScheme.error, onConfirm)
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

private val DialogFill = Color(0xFF1B1B1B)
