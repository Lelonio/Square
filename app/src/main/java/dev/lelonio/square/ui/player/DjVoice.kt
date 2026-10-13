package dev.lelonio.square.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lelonio.square.backend.spotify.SpotifyDj
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Spotify's DJ, drawn while it talks: three soft lights in its own colours,
 * drifting slowly and swelling with its voice, over the darkest of them.
 *
 * In place of the cover while a line is being said, since the song it would
 * show has not started; and, still, while a session is being prepared.
 * [idle] is that second case: the lights breathe on their own, with no voice
 * to follow.
 */
@Composable
internal fun DjVoice(modifier: Modifier = Modifier, idle: Boolean = false) {
    val colors by SpotifyDj.colors.collectAsStateWithLifecycle()
    val heard by SpotifyDj.level.collectAsStateWithLifecycle()
    val transition = rememberInfiniteTransition(label = "djVoice")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(9_000, easing = LinearEasing)),
        label = "djDrift",
    )
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(2_400, easing = LinearEasing)),
        label = "djBreath",
    )
    // Quick to rise and slower to fall, as a voice's level is read.
    val level by animateFloatAsState(
        targetValue = if (idle) 0.25f + 0.2f * sin(breath) else heard,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "djLevel",
    )

    val lights = colors.map(::Color)
    val ground = lights.fold(Color.Black) { dark, light -> lerp(dark, light, 0.12f) }

    Canvas(modifier) {
        drawRect(
            Brush.verticalGradient(
                listOf(lerp(ground, lights[0], 0.25f), ground, lerp(ground, lights[2], 0.2f)),
            ),
        )
        val unit = min(size.width, size.height)
        lights.forEachIndexed { index, light ->
            val turn = phase + index * 2.1f
            val center = Offset(
                size.width / 2 + cos(turn) * size.width * 0.14f,
                size.height * (0.3f + 0.2f * index) + sin(turn * 1.3f) * size.height * 0.04f,
            )
            val radius = unit * (0.42f + 0.06f * sin(turn)) * (0.9f + level * 0.55f)
            drawCircle(
                Brush.radialGradient(
                    listOf(light.copy(alpha = 0.85f), light.copy(alpha = 0.35f), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }
    }
}

/**
 * The DJ coming on: its lights over the whole screen, breathing on their own,
 * while the session is fetched and its first line made, which takes seconds.
 * Takes every touch meanwhile, so nothing else is started under it.
 */
@Composable
internal fun DjStarting() {
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxSize()
            .swallowTouches(),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        DjVoice(Modifier.matchParentSize(), idle = true)
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            DjMark(color = Color.White, fontSize = 28.sp)
            androidx.compose.foundation.layout.Spacer(Modifier.height(18.dp))
            androidx.compose.material3.Text(
                androidx.compose.ui.res.stringResource(dev.lelonio.square.R.string.spotify_dj_starting),
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

/** Swallows every touch, so what is under it cannot be pressed. */
private fun Modifier.swallowTouches(): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent().changes.forEach { it.consume() }
            }
        }
    }
