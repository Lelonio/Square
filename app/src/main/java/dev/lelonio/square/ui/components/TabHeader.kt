package dev.lelonio.square.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lelonio.square.ui.glass.pressable
import dev.lelonio.square.ui.theme.InkDim

/**
 * Who is listening, and on which service: the home page's provenance line and
 * picture, which every main tab's heading carries in the same places.
 */
@androidx.compose.runtime.Immutable
data class TabAccount(
    val service: String,
    val name: String,
    val avatarUrl: String?,
    val onOpenSettings: () -> Unit,
)

val LocalTabAccount = androidx.compose.runtime.staticCompositionLocalOf<TabAccount?> { null }

/**
 * The heading of a main tab, laid out as the home page's is, so the title
 * stands where the wordmark does on every tab: 18 under the top, a small line
 * over it (the account on the home page; here whatever the page has to say
 * about itself, or nothing, keeping its room), then 10, then a row 44 high
 * with the title in the wordmark's letters at the wordmark's height and the
 * page's own buttons at its end.
 */
@Composable
fun TabHeader(
    title: String,
    modifier: Modifier = Modifier,
    topPadding: Dp = 0.dp,
    /** The line over the title; null takes the account's, as the home page's. */
    overline: String? = null,
    /** The page's own buttons; null takes the account's picture. */
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val account = LocalTabAccount.current
    val line = overline ?: account?.let {
        listOf(it.service, it.name.takeIf { name -> name.isNotBlank() }).filterNotNull().joinToString(" · ")
    }
    Column(modifier.fillMaxWidth().padding(top = topPadding)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 20.dp, top = 18.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    line ?: " ",
                    style = MaterialTheme.typography.labelLarge,
                    color = InkDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer { alpha = if (line == null) 0f else 1f },
                )
                Box(
                    Modifier
                        .padding(top = 10.dp)
                        .height(44.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    // Raised by half the accents' room, so the capitals sit
                    // where the wordmark's do in a row of this height.
                    SquareTitle(
                        title,
                        height = TITLE_HEIGHT,
                        modifier = Modifier.offset(y = -TITLE_HEIGHT * 0.15f),
                    )
                }
            }
            Row(
                Modifier.height(44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (trailing != null) {
                    trailing()
                } else if (account != null) {
                    // The way into the settings, as on the home page.
                    ProfilePicture(
                        url = account.avatarUrl,
                        name = account.name,
                        size = 46.dp,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .pressable(account.onOpenSettings, pressedScale = 0.90f),
                        decodeSize = 46.dp,
                    )
                }
            }
        }
    }
}

/**
 * The wordmark's capitals in the home page's header: 22 high over a box of
 * 12 units, of which the capitals are 10.
 */
val TITLE_HEIGHT = (22f * 10f / 12f).dp
