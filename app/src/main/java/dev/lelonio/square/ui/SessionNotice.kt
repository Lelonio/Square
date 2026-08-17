package dev.lelonio.square.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.R
import dev.lelonio.square.ui.glass.pressable
import dev.lelonio.square.ui.player.GlassSurface
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * The one thing an expired Web API session should cost the listener: a tap.
 *
 * Spotify's own application, the one search and the home feed are metered
 * against, holds a token that lapses on its own schedule, and until now the only
 * sign of it was a search that quietly answered nothing and a settings screen
 * the user had to think to open. The client id is already stored and does not
 * expire, so the way back is the same OAuth flow with nothing to type: this says
 * what happened and runs it.
 */
@Composable
fun SessionExpiredNotice(
    visible: Boolean,
    backdrop: Backdrop,
    onReconnect: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier,
    ) {
        GlassSurface(
            backdrop = backdrop,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            surfaceColor = Color.White.copy(alpha = 0.10f),
        ) {
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.web_api_expired_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Text(
                    stringResource(R.string.web_api_expired_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.web_api_expired_action),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.pressable(onClick = onReconnect),
                    )
                    Text(
                        stringResource(R.string.later),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.pressable(onClick = onDismiss),
                    )
                }
            }
        }
    }
}
