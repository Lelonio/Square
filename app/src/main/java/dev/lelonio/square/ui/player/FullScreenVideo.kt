package dev.lelonio.square.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import dev.lelonio.square.ui.glass.backdrop.backdrops.layerBackdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import androidx.media3.common.Player
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.Pause
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.fill.SkipBack
import com.adamglin.phosphoricons.fill.SkipForward
import dev.lelonio.square.R
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CornersIn
import dev.lelonio.square.ui.library.formatDuration

/**
 * The video, and nothing else, for a phone held on its side.
 *
 * Turning the screen while something is playing is how everybody asks for a
 * video to fill the display, so that is what it does. What it is not is a
 * second player: the same session is playing, and the controls here are the
 * same ones, so this is a way of looking at what is already happening.
 *
 * The controls are drawn over the picture and fade out of the way, because in
 * this orientation the picture is the whole point; a tap brings them back.
 */
@Composable
fun FullScreenVideo(
    player: Player,
    attachKey: Any?,
    state: PlaybackState,
    positionMs: State<Long>,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
) {
    // The video's own shape, so it is fitted rather than stretched; see
    // rememberVideoRatio.
    val ratio = rememberVideoRatio(player, attachKey)

    var showControls by remember { mutableStateOf(true) }

    // Out of the way on their own, the way every video player does it. Only
    // while something is playing: paused, the controls are what the listener
    // is looking for.
    androidx.compose.runtime.LaunchedEffect(showControls, state.isPlaying, positionMs.value / 4000) {
        if (showControls && state.isPlaying) {
            kotlinx.coroutines.delay(CONTROLS_LINGER_MS)
            showControls = false
        }
    }

    // What the glass refracts here is the black behind the picture, recorded on
    // its own: a pane cannot sample a protected video, and asking it to would
    // leave the discs empty.
    val backdrop = rememberLayerBackdrop()

    // The status bar and the gesture pill go away with everything else.
    //
    // A phone turned on its side to watch something is not showing a page any
    // more, and leaving the system's own furniture on top of the picture is the
    // difference between a video player and a video in a window. Put back on
    // the way out, whatever the way out was.
    val context = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(context) {
        val window = (context as? android.app.Activity)?.window
        val controller = window?.let {
            androidx.core.view.WindowCompat.getInsetsController(it, it.decorView)
        }
        controller?.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = null, indication = null) {
                showControls = !showControls
            },
        contentAlignment = Alignment.Center,
    ) {
        // The recorded layer holds the black and nothing else, and it has to be
        // a sibling of the controls rather than their parent: a pane drawn
        // inside the layer it samples asks the render tree to draw itself to
        // draw itself, and the stack runs out.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .layerBackdrop(backdrop),
        )

        VideoSurface(player, attachKey, Modifier.aspectRatio(ratio))

        // A wash over the picture while the controls are up, so white text and
        // white glass have something to sit on. It goes with them.
        androidx.compose.animation.AnimatedVisibility(
            visible = showControls,
            enter = androidx.compose.animation.fadeIn(
                androidx.compose.animation.core.tween(180),
            ),
            exit = androidx.compose.animation.fadeOut(
                androidx.compose.animation.core.tween(220),
            ),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
            )
        }

        // Back to upright, with the controls: the way out for a rotation the
        // button asked for, which turning the phone does not undo.
        val context = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.animation.AnimatedVisibility(
            visible = showControls,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(220)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable { lockVideoOrientation(context, landscape = false) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    PhosphorIcons.Regular.CornersIn,
                    contentDescription = stringResource(R.string.exit_full_screen),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // What is playing, at the top, with the controls: on a screen with no
        // cover and no page around it, this is the only thing naming the song.
        androidx.compose.animation.AnimatedVisibility(
            visible = showControls,
            enter = androidx.compose.animation.fadeIn(
                androidx.compose.animation.core.tween(180),
            ),
            exit = androidx.compose.animation.fadeOut(
                androidx.compose.animation.core.tween(220),
            ),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            Column(Modifier.padding(horizontal = 28.dp, vertical = 22.dp)) {
                Text(
                    state.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    state.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = showControls,
            enter = androidx.compose.animation.fadeIn(
                androidx.compose.animation.core.tween(180),
            ),
            exit = androidx.compose.animation.fadeOut(
                androidx.compose.animation.core.tween(220),
            ),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides Color.White,
        ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 20.dp),
        ) {
            // The vertical player's own bar and its own discs of glass. Same
            // controls, same material: turning the phone changes where they sit
            // on the screen, not what the app is made of.
            GlassProgressBar(
                positionMs = positionMs,
                durationMs = state.durationMs,
                onSeek = onSeek,
                accentColor = Color.White,
                trackColor = Color.White.copy(alpha = 0.28f),
            )
            TimeRow(positionMs, state.durationMs)

            Spacer(Modifier.height(10.dp))

            Controls(
                state = state,
                backdrop = backdrop,
                onTogglePlay = onTogglePlay,
                onNext = onNext,
                onPrevious = onPrevious,
                onToggleShuffle = onToggleShuffle,
                onCycleRepeat = onCycleRepeat,
            )
        }
        }
        }
    }
}

/** How long the controls stay up before getting out of the picture. */
private const val CONTROLS_LINGER_MS = 3_500L

/**
 * Turns the app for the video: [landscape] true lies it on its side whichever
 * way the phone is held, false stands it back up, null hands the decision back
 * to the phone.
 */
fun lockVideoOrientation(context: android.content.Context, landscape: Boolean?) {
    var at: android.content.Context? = context
    while (at is android.content.ContextWrapper && at !is android.app.Activity) at = at.baseContext
    val activity = at as? android.app.Activity ?: return
    activity.requestedOrientation = when (landscape) {
        true -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        false -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        null -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}
