package dev.lelonio.square.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay

/**
 * Waiting for Spotify, with a way out.
 *
 * With signal and no data the wait can be long, and from here it looks like
 * any other: the listener cannot tell a slow answer from one that is not
 * coming. After a few seconds this offers what they can do either way, play
 * what is on the phone, instead of leaving them to watch a spinner.
 */
@Composable
fun ConnectingState(backdrop: Backdrop, onGoOffline: () -> Unit) {
    var offer by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(OFFER_AFTER_MS)
        offer = true
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator(color = Ink, strokeWidth = 2.dp)
            AnimatedVisibility(visible = offer, enter = fadeIn(tween(300))) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.connecting_slow),
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkDim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 40.dp, vertical = 4.dp),
                    )
                    LiquidButton(
                        onClick = onGoOffline,
                        backdrop = backdrop,
                        contentHeight = 50.dp,
                    ) {
                        Text(
                            stringResource(R.string.go_offline),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Ink,
                            modifier = Modifier.padding(horizontal = 18.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Long enough for an ordinary start to be over before it shows. */
private const val OFFER_AFTER_MS = 3_000L
