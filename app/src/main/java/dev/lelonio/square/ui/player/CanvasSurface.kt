package dev.lelonio.square.ui.player

import dev.lelonio.square.ui.components.copyFrame
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
    /**
     * The colour the clip's own foot is, read back off the picture.
     *
     * The field the clip dissolves into used to be built from the cover, and a
     * Canvas is very often nothing like its sleeve — a bright clip ending on a
     * dark red field is two pictures meeting, which is the seam this whole
     * treatment exists to avoid. Read slowly and off a thumbnail: a colour that
     * tracked every cut would strobe, and this is meant to read as the light the
     * clip is throwing.
     */
    onTone: (List<Color>) -> Unit = {},
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

    // The view the picture is actually drawn into, kept so it can be read back.
    var texture by remember(url) { mutableStateOf<android.view.TextureView?>(null) }
    val tone = rememberUpdatedState(onTone)
    LaunchedEffect(texture) {
        val view = texture ?: return@LaunchedEffect
        while (true) {
            if (!view.isAvailable) {
                kotlinx.coroutines.delay(TONE_INTERVAL_MS)
                continue
            }
            // Off the main thread, copy and sums alike; see copyFrame.
            val frame = view.copyFrame(TONE_SAMPLE, TONE_SAMPLE)
            if (frame != null) {
                val columns = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    frame.footColumns().also { frame.recycle() }
                }
                tone.value(columns)
            }
            kotlinx.coroutines.delay(TONE_INTERVAL_MS)
        }
    }

    AndroidView(
        factory = { ctx ->
            // Cropping to fill and the transparent shutter come from the layout;
            // canvases are 9:16 and the screen rarely is, so the alternative to
            // cropping is bars.
            val view = android.view.LayoutInflater.from(ctx)
                .inflate(R.layout.canvas_surface, null) as PlayerView
            view.player = exoPlayer
            // PlayerView builds the surface itself from `surface_type`, so the
            // TextureView is found rather than made.
            texture = view.videoSurfaceView as? android.view.TextureView
            view
        },
        // Nothing here changes with recomposition; the player is swapped by
        // remember(url) when the track does.
        update = {},
        modifier = modifier,
    )
}

/**
 * The foot of a frame, as a handful of colours across its width.
 *
 * Not one average. What a still cover does below its own edge is carry its last
 * row downwards, so a red shape at the bottom of the picture goes on being red
 * under it while a dark corner stays dark. One colour for the whole width
 * throws exactly that away — a frame with a red flood across the middle and
 * black at the sides would come out an even maroon. These are column averages
 * of the bottom band, which the field below paints as a gradient across.
 *
 * The band, not the whole frame: what the fade meets is the foot of the
 * picture, and a clip whose top half is a bright sky and whose bottom is a dark
 * street averages to neither.
 */
private fun android.graphics.Bitmap.footColumns(): List<Color> {
    val from = (height * 0.72f).toInt().coerceIn(0, height - 1)
    val band = (height - from).coerceAtLeast(1)
    val step = (width / TONE_COLUMNS).coerceAtLeast(1)
    return (0 until TONE_COLUMNS).map { column ->
        val x0 = (column * step).coerceAtMost(width - 1)
        val x1 = (x0 + step).coerceAtMost(width)
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0
        for (y in from until height) {
            for (x in x0 until x1) {
                val pixel = getPixel(x, y)
                r += android.graphics.Color.red(pixel)
                g += android.graphics.Color.green(pixel)
                b += android.graphics.Color.blue(pixel)
                count++
            }
        }
        if (count == 0) Color.Black
        else Color(r / count / 255f, g / count / 255f, b / count / 255f)
    }.also { require(band > 0) }
}

/**
 * How many columns the foot is read as.
 *
 * Eight kept the shape and showed the machinery: a gradient interpolates
 * straight between its stops, so eight of them across a phone is a stop every
 * 45 points and the eye finds each one — the field came out as vertical bands.
 * Twenty-four is one every fifteen points, which reads as a wash.
 */
private const val TONE_COLUMNS = 24

/** How often the clip is read back, in milliseconds. */
private const val TONE_INTERVAL_MS = 900L

/**
 * Side of the thumbnail the colours are taken from.
 *
 * Wide enough that each of the columns above still averages several pixels
 * rather than reading one, which is the difference between a colour and a
 * speck.
 */
private const val TONE_SAMPLE = 96
