package dev.lelonio.square.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Says that Spotify's DJ is the one choosing the songs, and talking between
 * them: a small tag in the ink of whatever it sits on, beside the song in the
 * mini player and beside the source at the top of the player.
 */
@Composable
internal fun DjMark(
    color: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 10.sp,
) {
    Text(
        "DJ",
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
        ),
        color = color,
        maxLines = 1,
        modifier = modifier
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** Whether [contextUri] is the DJ's queue. */
internal fun isDjQueue(contextUri: String?) =
    dev.lelonio.square.backend.spotify.SpotifyDj.isDj(contextUri)
