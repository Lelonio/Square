package dev.lelonio.square.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * A cover that moves.
 *
 * Some records have one: a few seconds of the sleeve animating, which the
 * reference plays at the top of the page instead of showing a still. It is a
 * picture, not a video — there is no sound, no controls, and it loops.
 *
 * Its own player rather than the app's. The one in the service is what the
 * listener is playing and is bound to a media session, a notification and the
 * car; handing it a silent loop of album art would replace the song on the lock
 * screen. This one holds no audio focus and is released with the screen.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun MotionCover(
    url: String,
    modifier: Modifier = Modifier,
    /**
     * Small copies of the frames as they play, for what is drawn out of the
     * cover around it: see HeroBackdrop, whose blur and extension were made
     * from the still and stayed on the first frame while the cover moved
     * above them. Null reads nothing.
     */
    onFrame: ((android.graphics.Bitmap) -> Unit)? = null,
) {
    val context = LocalContext.current
    // The surface the frames are read from, once the view exists.
    val surface = remember { arrayOfNulls<android.view.TextureView>(1) }

    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            // Explicitly not asking for focus: what is playing keeps playing.
            setAudioAttributes(AudioAttributes.DEFAULT, false)
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ALL
            setMediaItem(MediaItem.fromUri(url))
            playWhenReady = true
            prepare()
        }
    }

    // Stopped while the app is away. A loop nobody is looking at is a radio
    // that costs battery and data for a picture on a screen that is off.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player.pause()
                Lifecycle.Event.ON_START -> player.play()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.release()
        }
    }

    if (onFrame != null) {
        val latest by rememberUpdatedState(onFrame)
        LaunchedEffect(player) {
            // Nothing is read before the first frame: until then the surface
            // holds nothing, and a blank copy would put the blur back to black.
            val rendered = CompletableDeferred<Unit>()
            val listener = object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    rendered.complete(Unit)
                }
            }
            player.addListener(listener)
            try {
                rendered.await()
                while (true) {
                    val view = surface[0]
                    // Only while it moves: a paused loop is a still, and the
                    // last copy read is already that still.
                    if (view != null && view.isAvailable && player.isPlaying &&
                        view.width > 0 && view.height > 0
                    ) {
                        val height = (FRAME_PX.toLong() * view.height / view.width).toInt()
                            .coerceAtLeast(1)
                        view.getBitmap(FRAME_PX, height)?.let { latest(it) }
                    }
                    delay(FRAME_EVERY_MS)
                }
            } finally {
                player.removeListener(listener)
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            // Inflated, for the surface type; see the layout's own note. Built
            // in code this is a SurfaceView — a second window under the app's,
            // which does not fade or move with the composition, so a page
            // leaving the screen left its video behind for a moment after
            // everything else had gone.
            val view = android.view.LayoutInflater.from(ctx)
                .inflate(dev.lelonio.square.R.layout.motion_cover, null) as PlayerView
            view.apply {
                useController = false
                // Filling the frame, cropping what does not fit: the header is
                // whatever shape the phone is, and letterboxing a cover leaves
                // two bars of black across the top of the page.
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                this.player = player
                surface[0] = videoSurfaceView as? android.view.TextureView
            }
        },
        update = { it.player = player },
        // Released with the composition rather than left to the view pool: the
        // surface has to go when the page does.
        onRelease = {
            it.player = null
            surface[0] = null
        },
        modifier = modifier,
    )
}

/**
 * How wide the copies of the frames are read, and how often.
 *
 * Small, because what is made of them is a blur and a band of colour, and a
 * read of a frame is a copy off the GPU. Often enough that the blur under a
 * cover in motion follows it rather than stepping after it.
 */
private const val FRAME_PX = 48
private const val FRAME_EVERY_MS = 66L
