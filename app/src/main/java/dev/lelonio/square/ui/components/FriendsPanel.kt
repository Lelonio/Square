package dev.lelonio.square.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.ui.glass.backdrop.drawBackdrop
import dev.lelonio.square.ui.glass.backdrop.effects.blur
import dev.lelonio.square.R
import dev.lelonio.square.data.FriendListen
import dev.lelonio.square.ui.glass.pressable
import dev.lelonio.square.ui.player.GlassSurface
import dev.lelonio.square.ui.theme.softShadow

/**
 * Who the account follows, and what they were last heard playing.
 *
 * Spotify's own friend activity, which the desktop client keeps down its side
 * and the phone app does not have at all. A panel rather than a page: it is
 * something to glance at on the way to something else, and a tab of its own
 * would make a whole destination out of a list that is often three lines long.
 *
 * Every row is a way in. Somebody's song is a song, so tapping one offers it
 * exactly as a link to it would.
 */
@Composable
fun BoxScope.FriendsPanel(
    visible: Boolean,
    friends: List<FriendListen>,
    backdrop: Backdrop,
    onOpenTrack: (FriendListen) -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(180)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RectangleShape },
                    effects = { blur(28f.dp.toPx()) },
                    onDrawSurface = { drawRect(Color.Black.copy(alpha = 0.46f)) },
                )
                .clickable(interactionSource = null, indication = null, onClick = onDismiss),
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(tween(240), initialScale = 0.9f) + fadeIn(tween(160)),
        exit = scaleOut(tween(160), targetScale = 0.94f) + fadeOut(tween(140)),
        modifier = Modifier.align(Alignment.Center),
    ) {
        val shape = RoundedCornerShape(32.dp)
        GlassSurface(
            backdrop = backdrop,
            shape = shape,
            surfaceColor = Color.White.copy(alpha = 0.08f),
            blurScale = 23.0f,
            modifier = Modifier
                .padding(horizontal = 22.dp)
                .fillMaxWidth()
                .clip(shape)
                .softShadow(shape, elevation = 26.dp, spot = 0.35f)
                .clickable(interactionSource = null, indication = null) {},
        ) {
            Column(Modifier.padding(vertical = 22.dp)) {
                Text(
                    stringResource(R.string.friends_listening),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 22.dp),
                )

                if (friends.isEmpty()) {
                    // Says why it is empty, because there are three reasons and
                    // none of them is a failure the listener could act on.
                    Text(
                        stringResource(R.string.friends_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
                    )
                    return@Column
                }

                LazyColumn(
                    Modifier
                        .padding(top = 6.dp)
                        // Bounded, so a well-connected account gets a list that
                        // scrolls rather than a panel taller than the screen.
                        .heightIn(max = 420.dp),
                ) {
                    items(friends, key = { it.userUri + it.trackUri }) { friend ->
                        FriendRow(friend) { onOpenTrack(friend) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FriendRow(friend: FriendListen, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick, shape = RoundedCornerShape(18.dp), pressedScale = 0.98f)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Artwork(
                url = friend.avatarUrl,
                title = friend.userName,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape),
                corner = 22.dp,
                decodeSize = 44.dp,
            )
            // The cover, tucked into the corner of the person. Two pictures of
            // equal size would be a row about two things; this one is about a
            // person, and what they are playing is the caption.
            Artwork(
                url = friend.artworkUrl,
                title = friend.trackName,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(start = 20.dp, top = 20.dp)
                    .size(22.dp),
                corner = 6.dp,
                decodeSize = 22.dp,
            )
        }

        Column(
            Modifier
                .weight(1f)
                .padding(start = 14.dp),
        ) {
            Text(
                friend.userName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${friend.trackName} · ${friend.artist}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Where they were playing it from, and how long ago. Both are what
            // makes this activity rather than a list of names.
            val detail = listOfNotNull(
                friend.contextName.takeIf { it.isNotEmpty() },
                ago(friend.at),
            ).joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.45f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * How long ago, in the coarsest unit that still says something.
 *
 * Minutes for the last hour, then hours, then days. A timestamp to the second
 * would be both more precise and less useful: nobody reads this to know when,
 * only whether it was now.
 */
@Composable
private fun ago(at: Long): String? {
    val minutes = (System.currentTimeMillis() - at) / 60_000
    return when {
        minutes < 0 -> null
        minutes < 1 -> stringResource(R.string.just_now)
        minutes < 60 -> stringResource(R.string.minutes_ago, minutes)
        minutes < 60 * 24 -> stringResource(R.string.hours_ago, minutes / 60)
        else -> stringResource(R.string.days_ago, minutes / (60 * 24))
    }
}
