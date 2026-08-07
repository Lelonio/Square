package dev.lelonio.square.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lelonio.square.R
import dev.lelonio.square.backend.BackendId
import dev.lelonio.square.ui.components.SquareWordmark
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim

/**
 * The first question a fresh install asks: where the music comes from.
 *
 * Ahead of the tutorial, and that order is the point. The whole setup the
 * tutorial walks through — the Spotify login, the registered Web API
 * application, the redirect URI — exists only for Spotify. Asking someone to
 * do all of it and *then* telling them there was another option that needs none
 * of it would be asking them to redo the decision after paying for it.
 *
 * Shown once. Changing the answer later lives in the settings, where a choice
 * that restarts playback belongs.
 */
@Composable
fun BackendChoiceScreen(onChoose: (BackendId) -> Unit) {
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        Modifier
            .fillMaxSize()
            // Opaque, like the tutorial it precedes: there is nothing behind
            // this worth showing through, and glass over an unconfigured home
            // page would be showing the user the problem, not the choice.
            .background(Color(0xFF101012))
            .clickable(interactionSource = null, indication = null) {},
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = statusBar + 24.dp, bottom = navBar + 24.dp)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            SquareWordmark(height = 22.dp, modifier = Modifier.padding(bottom = 28.dp))

            Text(
                stringResource(R.string.backend_choice_title),
                style = MaterialTheme.typography.displayLarge,
                color = Ink,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.backend_choice_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = InkDim,
            )

            Spacer(Modifier.height(32.dp))

            SourceCard(
                title = stringResource(R.string.backend_spotify),
                description = stringResource(R.string.backend_choice_spotify_note),
                onClick = { onChoose(BackendId.SPOTIFY) },
            )
            Spacer(Modifier.height(14.dp))
            SourceCard(
                title = stringResource(R.string.backend_youtube),
                description = stringResource(R.string.backend_choice_youtube_note),
                onClick = { onChoose(BackendId.YOUTUBE_MUSIC) },
            )

            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.backend_choice_changeable),
                style = MaterialTheme.typography.bodySmall,
                color = InkDim,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * One of the two answers.
 *
 * A card rather than a radio row: this is the only thing on screen and there is
 * no "confirm" — tapping one *is* the answer, so each needs enough room to say
 * what picking it means before it is picked.
 */
@Composable
private fun SourceCard(title: String, description: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xFF1A1A1E))
            .border(1.dp, Color(0x1AFFFFFF), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Ink,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
        )
    }
}
