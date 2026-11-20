package dev.lelonio.square.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.SpotifyLogo
import com.adamglin.phosphoricons.fill.YoutubeLogo
import com.adamglin.phosphoricons.regular.CaretRight
import dev.lelonio.square.R
import dev.lelonio.square.backend.BackendId
import dev.lelonio.square.ui.components.AppGlyph
import dev.lelonio.square.ui.components.SquareWordmark
import dev.lelonio.square.ui.glass.pressable
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim

/**
 * The first question a fresh install asks: where the music comes from.
 *
 * Ahead of the tutorial, and that order is the point. The whole setup the
 * tutorial walks through (the Spotify login, the registered Web API
 * application, the redirect URI) exists only for Spotify. Asking someone to
 * do all of it and *then* telling them there was another option that needs none
 * of it would be asking them to redo the decision after paying for it.
 *
 * Shown once. Changing the answer later lives in the settings, where a choice
 * that restarts playback belongs. Drawn like the guide that follows it, so the
 * two read as one setup; and as the first thing a new install shows, it opens
 * with the app's own mark, with the question under it and the two answers
 * lower down, where the thumb is.
 */
@Composable
fun BackendChoiceScreen(onChoose: (BackendId) -> Unit) {
    SetupScaffold(top = {}) {
        AppIcon()
        Spacer(Modifier.height(24.dp))
        SquareWordmark(height = 26.dp)
        Spacer(Modifier.height(28.dp))
        StepHeader(stringResource(R.string.backend_choice_title))
        ProseText(stringResource(R.string.backend_choice_subtitle))
        // A row each rather than a radio and a "confirm": tapping one is the
        // answer, so each says what picking it means before it is picked.
        Column(
            Modifier
                .padding(top = 22.dp)
                .setupCard(),
        ) {
            SourceCard(
                logo = PhosphorIcons.Fill.SpotifyLogo,
                title = stringResource(R.string.backend_spotify),
                description = stringResource(R.string.backend_choice_spotify_note),
                needs = stringResource(R.string.backend_choice_spotify_needs),
                onClick = { onChoose(BackendId.SPOTIFY) },
            )
            ListDivider()
            SourceCard(
                logo = PhosphorIcons.Fill.YoutubeLogo,
                title = stringResource(R.string.backend_youtube),
                description = stringResource(R.string.backend_choice_youtube_note),
                needs = stringResource(R.string.backend_choice_youtube_needs),
                onClick = { onChoose(BackendId.YOUTUBE_MUSIC) },
            )
        }
    }
}

/**
 * One of the two answers: the service's mark, what it brings, what it asks.
 *
 * The mark in ink like everything else on the page. It says which service at
 * a glance, and in the brand's own colour it would be the one loud thing on a
 * screen that is otherwise asking a quiet question.
 */
@Composable
private fun SourceCard(
    logo: ImageVector,
    title: String,
    description: String,
    needs: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .pressable(onClick, pressedScale = 0.98f)
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Ink.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(logo, contentDescription = null, tint = Ink, modifier = Modifier.size(26.dp))
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 14.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
                modifier = Modifier.padding(top = 1.dp),
            )
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = Prose,
                modifier = Modifier.padding(top = 3.dp),
            )
            Text(
                needs,
                style = MaterialTheme.typography.bodySmall,
                color = InkDim,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Icon(
            PhosphorIcons.Regular.CaretRight,
            contentDescription = null,
            tint = InkDim,
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .padding(start = 8.dp)
                .size(18.dp),
        )
    }
}
