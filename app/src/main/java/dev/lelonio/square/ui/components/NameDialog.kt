package dev.lelonio.square.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lelonio.square.R
import dev.lelonio.square.ui.glass.LiquidButton
import dev.lelonio.square.ui.glass.LocalAppBackdrop

/**
 * Asks for one line of text — a playlist's name, on the way in or on the way
 * to being changed: upstream's GlassSheet, with the field focused and the
 * keyboard under it.
 */
@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    placeholder: String = stringResource(R.string.playlist_name),
) {
    var text by remember(initial) { mutableStateOf(initial) }
    GlassSheet(
        visible = true,
        onDismiss = onDismiss,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
        scrollable = false,
    ) {
        SheetTitle(title)
        GlassSearchBar(
            query = text,
            onQuery = { text = it },
            placeholder = placeholder,
            icon = null,
            imeAction = ImeAction.Done,
            autoFocus = true,
            onSubmit = { if (it.isNotBlank()) onConfirm(it) },
            modifier = Modifier.padding(top = 16.dp),
            // The sheet is drawn by the host, above the app and outside the
            // page that provides the app's backdrop: the field reading that
            // one closed the app as the sheet opened (saving an effect preset,
            // naming an equalizer curve, renaming a playlist).
            backdrop = LocalGlassSheetHost.current?.backdrop,
        )
        // Nothing to create or save while the field is empty, and a playlist
        // called nothing is not something either service will take anyway.
        SheetButtons(
            cancel = onDismiss,
            confirmLabel = confirmLabel,
            confirmEnabled = text.isNotBlank(),
            confirm = { if (text.isNotBlank()) onConfirm(text) },
        )
    }
}

/** A sheet's heading, centred at 17 as a sheet's navigation title is. */
@Composable
internal fun SheetTitle(title: String) {
    Text(
        title,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (dev.lelonio.square.ui.theme.LocalLightTheme.current) Color.Black else Color.White,
        style = MaterialTheme.typography.titleMedium,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Cancel and the answer, two glass capsules side by side, the answer lit. */
@Composable
internal fun SheetButtons(
    cancel: () -> Unit,
    confirmLabel: String,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
    cancelLabel: String = stringResource(R.string.cancel),
    confirm: () -> Unit,
) {
    val light = dev.lelonio.square.ui.theme.LocalLightTheme.current
    val ink = if (light) Color.Black else Color.White
    val accent = if (destructive) {
        if (light) Color(0xFFFF3B30) else Color(0xFFFF453A)
    } else {
        MaterialTheme.colorScheme.primary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LiquidButton(
            onClick = cancel,
            backdrop = LocalGlassSheetHost.current?.backdrop ?: LocalAppBackdrop.current,
            modifier = Modifier.weight(1f),
        ) {
            Text(cancelLabel, color = ink, fontSize = 17.sp)
        }
        LiquidButton(
            onClick = confirm,
            backdrop = LocalGlassSheetHost.current?.backdrop ?: LocalAppBackdrop.current,
            enabled = confirmEnabled,
            tint = accent,
            modifier = Modifier.weight(1f),
        ) {
            Text(confirmLabel, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
