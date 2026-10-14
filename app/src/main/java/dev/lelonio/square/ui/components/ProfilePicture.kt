package dev.lelonio.square.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.User
import dev.lelonio.square.ui.theme.Ink

/**
 * A person's picture, round: the account's own where it has one.
 *
 * Without one it is not a cover. The generated cover a playlist falls back to
 * is a large letter laid out for a square, and cut into a circle it showed half
 * of one. A name gets its initials, whole and centred; no name, which is
 * nobody signed in, gets the outline of a person.
 */
@Composable
fun ProfilePicture(
    url: String?,
    name: String?,
    /**
     * How big it is drawn, for the corner and the initials. Passed rather than
     * measured: measuring meant composing late, and a late composition is what
     * the focus search went looking in when the sign-in page closed — the app
     * stopped with Compose's "pending composition has not been applied".
     */
    size: Dp,
    modifier: Modifier = Modifier,
    decodeSize: Dp = 0.dp,
) {
    if (url != null) {
        Artwork(
            url = url,
            title = name.orEmpty(),
            modifier = modifier,
            corner = size / 2,
            decodeSize = decodeSize,
        )
        return
    }
    Box(
        modifier
            .clip(CircleShape)
            .background(
                Brush.linearGradient(listOf(Ink.copy(alpha = 0.16f), Ink.copy(alpha = 0.07f))),
            ),
        contentAlignment = Alignment.Center,
    ) {
        val initials = initialsOf(name)
        if (initials.isEmpty()) {
            Icon(
                PhosphorIcons.Fill.User,
                contentDescription = null,
                tint = Ink.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxSize(0.5f),
            )
        } else {
            val side = size
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    initials,
                    style = TextStyle(
                        fontSize = with(androidx.compose.ui.platform.LocalDensity.current) {
                            (side * if (initials.length > 1) 0.36f else 0.42f).toSp()
                        },
                        fontWeight = FontWeight.SemiBold,
                        color = Ink.copy(alpha = 0.8f),
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

/** Up to two initials: the first and last words' first letters. */
private fun initialsOf(name: String?): String {
    val words = name.orEmpty().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return ""
    val first = words.first().first()
    val last = words.drop(1).lastOrNull()?.first()
    return listOfNotNull(first, last).joinToString("") { it.uppercase() }
}
