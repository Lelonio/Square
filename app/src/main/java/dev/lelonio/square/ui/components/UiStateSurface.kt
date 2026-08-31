package dev.lelonio.square.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.lelonio.square.R
import dev.lelonio.square.ui.glass.LiquidButton
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim

/** Shared loading/empty/error treatment so screens do not invent their own state language. */
@Composable
fun UiStateSurface(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actionLabel: String? = null,
    backdrop: Backdrop? = null,
    onAction: (() -> Unit)? = null,
    loading: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(PaddingValues(horizontal = 24.dp, vertical = 32.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = Ink,
                strokeWidth = 2.dp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = Ink,
            textAlign = TextAlign.Center,
        )
        body?.takeIf(String::isNotBlank)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = InkDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!loading && actionLabel != null && onAction != null && backdrop != null) {
            LiquidButton(onClick = onAction, backdrop = backdrop) {
                Text(actionLabel, color = Ink)
            }
        }
    }
}

@Composable
fun ErrorState(
    message: String,
    backdrop: Backdrop,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) = UiStateSurface(
    title = stringResource(R.string.could_not_load),
    body = message,
    actionLabel = onRetry?.let { stringResource(R.string.retry) },
    backdrop = backdrop,
    onAction = onRetry,
    modifier = modifier,
)
