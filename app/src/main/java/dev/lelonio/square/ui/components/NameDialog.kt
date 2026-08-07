package dev.lelonio.square.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.lelonio.square.R
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim

/**
 * Asks for one line of text — a playlist's name, on the way in or on the way to
 * being changed.
 *
 * Its own dialog rather than a sheet: this is a question with an answer, and
 * the keyboard has to come up over whatever is behind it. Built out of the
 * app's own surfaces for the same reason every other control is.
 */
@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
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

            Box(
                Modifier
                    .padding(top = 18.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(FieldFill)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (text.isEmpty()) {
                    Text(
                        stringResource(R.string.playlist_name),
                        style = MaterialTheme.typography.bodyLarge,
                        color = InkDim,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
                    cursorBrush = SolidColor(Ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                Modifier
                    .padding(top = 18.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                DialogAction(stringResource(R.string.cancel), InkDim, onDismiss)
                // Nothing to create or save while the field is empty, and a
                // playlist called nothing is not something either service will
                // take anyway.
                DialogAction(
                    confirmLabel,
                    if (text.isBlank()) InkDim else Ink,
                ) { if (text.isNotBlank()) onConfirm(text) }
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

/** Opaque: a dialog floats over its own scrim, with no artwork to refract. */
private val DialogFill = Color(0xFF1B1B1B)

private val FieldFill = Color.White.copy(alpha = 0.08f)
