package dev.lelonio.square.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.lelonio.square.R

/**
 * The track's Canvas: a few seconds of video, looping behind the player.
 *
 * A second player instance, deliberately. The audio still comes from librespot —
 * this one is muted and only ever renders pictures. Feeding the clip through the
 * media session's player instead would mean the notification, the lock screen
 * and the queue all thought a video was the current item.
 *
 * Rendered into a TextureView, which is the whole reason this is inflated from a
 * layout instead of built in code — `surface_type` has no setter.
 *
 * A SurfaceView, the default, does render the clip: that much was checked, and
 * an earlier note here recorded it as proof that the surface type did not
 * matter. It does. A SurfaceView is composited by the system in its own window,
 * beneath the app's, and is therefore invisible to anything that records the
 * view hierarchy into a graphics layer — which is exactly what the glass panes
 * sample. They were refracting an empty layer, so they came out as flat
 * translucent rectangles with the video showing through them rather than in
 * them. A TextureView draws like any other view and can be recorded.
 *
 * Follows [isPlaying] so pausing the music stills the picture too; a clip that
 * keeps looping over a paused track reads as the app having lost track of
 * itself.
 */
@UnstableApi
@Composable
fun CanvasSurface(
    url: String,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    /**
     * Called when there is actually a picture on the surface.
     *
     * Between `prepare()` and the first decoded frame a TextureView is empty,
     * and on a slow connection that is a second or more of nothing where the
     * cover used to be. The caller keeps the cover up until this fires.
     */
    onFirstFrame: () -> Unit = {},
) {
    val context = LocalContext.current

    val exoPlayer = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            repeatMode = Player.REPEAT_MODE_ONE
            // Canvases ship with an audio track often enough to matter, and
            // playing it would put a second sound over the music.
            volume = 0f
            prepare()
        }
    }

    LaunchedEffect(exoPlayer, isPlaying) {
        exoPlayer.playWhenReady = isPlaying
    }

    val ready = rememberUpdatedState(onFirstFrame)
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                ready.value()
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        factory = { ctx ->
            // Cropping to fill and the transparent shutter come from the layout;
            // canvases are 9:16 and the screen rarely is, so the alternative to
            // cropping is bars.
            val view = android.view.LayoutInflater.from(ctx)
                .inflate(R.layout.canvas_surface, null) as PlayerView
            view.player = exoPlayer
            view
        },
        // Nothing here changes with recomposition; the player is swapped by
        // remember(url) when the track does.
        update = {},
        modifier = modifier,
    )
}
