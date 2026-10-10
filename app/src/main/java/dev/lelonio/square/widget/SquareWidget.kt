package dev.lelonio.square.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.common.util.concurrent.MoreExecutors
import dev.lelonio.square.R
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.playback.ACTION_OPEN_PLAYER
import dev.lelonio.square.playback.PlaybackService
import dev.lelonio.square.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Square on the home screen: what is playing, its controls, the playlists last
 * opened, and the mark that opens the app.
 *
 * Drawn by the launcher from RemoteViews, so it is redrawn by being told:
 * [publish] from the playback service as the song or the state changes, and
 * [refresh] when the playlists change. What it shows is kept on disk, so a
 * widget added, resized or brought back after a restart draws the last song
 * without waiting for the music to start.
 */
open class SquareWidget : AppWidgetProvider() {

    /**
     * The same widget, listed again at two by two: the square the small
     * widgets of other phones are, which a launcher otherwise only reaches by
     * resizing the wide one down.
     */
    class Tile : SquareWidget()

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        refresh(context)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PREVIOUS, ACTION_TOGGLE, ACTION_NEXT -> press(context, intent.action!!)
            else -> super.onReceive(context, intent)
        }
    }

    /**
     * A button pressed: handed to the player through a controller, as the app's
     * own screens do.
     *
     * Not as a media key sent to the service: started that way with nothing
     * loaded, the service has to come to the foreground with nothing to show,
     * and Android ends the app for it. Connecting starts the service as an
     * ordinary bound one, which may then put itself in the foreground once the
     * music actually plays.
     */
    private fun press(context: Context, action: String) {
        val pending = goAsync()
        val app = context.applicationContext
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            val controller = runCatching { future.get() }.getOrNull()
            try {
                when (action) {
                    ACTION_PREVIOUS -> controller?.seekToPrevious()
                    ACTION_NEXT -> controller?.seekToNext()
                    ACTION_TOGGLE -> controller?.let { if (it.isPlaying) it.pause() else it.play() }
                }
            } finally {
                controller?.release()
                pending.finish()
            }
        }, MoreExecutors.directExecutor())
    }

    /** What the widget shows of the song. */
    data class NowPlaying(
        val title: String,
        val artist: String,
        val artworkUri: String?,
        val playing: Boolean,
    )

    companion object {
        private const val ACTION_PREVIOUS = "dev.lelonio.square.widget.PREVIOUS"
        private const val ACTION_TOGGLE = "dev.lelonio.square.widget.TOGGLE"
        private const val ACTION_NEXT = "dev.lelonio.square.widget.NEXT"
        const val ACTION_OPEN_PLAYLIST = "dev.lelonio.square.widget.OPEN_PLAYLIST"
        const val EXTRA_URI = "dev.lelonio.square.widget.URI"
        const val EXTRA_NAME = "dev.lelonio.square.widget.NAME"
        const val EXTRA_ARTWORK = "dev.lelonio.square.widget.ARTWORK"

        private const val PREFS = "square_widget"
        private const val PLAYLIST_SLOTS = 4

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var drawing: Job? = null
        private var last: NowPlaying? = null

        /** A new song or state from the playback service; ignored when nothing changed. */
        fun publish(context: Context, now: NowPlaying) {
            if (now == last) return
            last = now
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("title", now.title)
                .putString("artist", now.artist)
                .putString("artwork", now.artworkUri)
                .putBoolean("playing", now.playing)
                .apply()
            refresh(context)
        }

        /** Draws every Square widget again from what is kept. */
        fun refresh(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, SquareWidget::class.java)) +
                manager.getAppWidgetIds(ComponentName(app, Tile::class.java))
            if (ids.isEmpty()) return
            drawing?.cancel()
            drawing = scope.launch {
                val now = stored(app)
                val backend = (app as SquareApplication).preferences.backend.value
                val playlists = app.widgetPlaylists.recent(backend).take(PLAYLIST_SLOTS)
                val density = app.resources.displayMetrics.density
                val cover = now?.artworkUri?.let { load(app, it, 512) }
                val covers = playlists.map { entry -> entry.artworkUrl?.let { load(app, it, 192) } }
                ids.forEach { id ->
                    val options = manager.getAppWidgetOptions(id)
                    val views = build(app, options, now, cover, playlists, covers, density)
                    runCatching { manager.updateAppWidget(id, views) }
                }
            }
        }

        private fun stored(context: Context): NowPlaying? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val title = prefs.getString("title", null)?.takeIf { it.isNotBlank() } ?: return null
            return NowPlaying(title, prefs.getString("artist", "").orEmpty(), prefs.getString("artwork", null), prefs.getBoolean("playing", false))
        }

        private suspend fun load(context: Context, data: String, size: Int): Bitmap? = runCatching {
            val result = context.imageLoader.execute(
                ImageRequest.Builder(context).data(data).size(size).allowHardware(false).build(),
            )
            (result as? SuccessResult)?.drawable?.toBitmap()
        }.getOrNull()

        /** The arrangements, by what fits; see [shapeFor]. */
        private enum class Shape(val layout: Int) {
            /** Wide and tall: song and controls on one line, playlists below. */
            LARGE(R.layout.widget_square),
            /** Wide, one row: the mark, the song, all three controls. */
            ROW(R.layout.widget_square_small),
            /** Narrow, one row: the song, play and next. */
            COMPACT(R.layout.widget_square_compact),
            /** Narrow and tall: the song above its controls, playlists if they fit. */
            COLUMN(R.layout.widget_square_column),
            /** Narrow and not so tall: the same, smaller, and no playlists. */
            COLUMN_TIGHT(R.layout.widget_square_column_tight),
            /** Two by two: the cover large, the song under it, the controls stacked beside. */
            TILE(R.layout.widget_square_tile),
        }

        /**
         * Chosen from the widget's own size, in the portrait it is mostly seen
         * in, and chosen again whenever it is resized (onAppWidgetOptionsChanged).
         *
         * Two fixed arrangements were not enough: between them a three-by-three
         * widget got the wide one, and the title was squeezed to an ellipsis
         * between the cover and the buttons. A title next to three buttons
         * needs [INLINE_WIDTH]; below that the buttons go under the song.
         */
        private fun shapeFor(widthDp: Int, heightDp: Int): Shape = when {
            // Too narrow for three buttons side by side, and tall enough to be
            // a square rather than a strip.
            widthDp < TILE_WIDTH && heightDp >= TILE_MIN_HEIGHT -> Shape.TILE
            heightDp < TALL_HEIGHT -> if (widthDp >= INLINE_WIDTH) Shape.ROW else Shape.COMPACT
            widthDp >= INLINE_WIDTH -> Shape.LARGE
            heightDp < COLUMN_HEIGHT -> Shape.COLUMN_TIGHT
            else -> Shape.COLUMN
        }

        private fun build(
            context: Context,
            options: Bundle,
            now: NowPlaying?,
            cover: Bitmap?,
            playlists: List<WidgetPlaylists.Entry>,
            covers: List<Bitmap?>,
            density: Float,
        ): RemoteViews {
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300).coerceAtLeast(60)
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180).coerceAtLeast(40)
            return views(context, shapeFor(width, height), now, cover, playlists, covers, width, height, density)
        }

        private fun views(
            context: Context,
            shape: Shape,
            now: NowPlaying?,
            cover: Bitmap?,
            playlists: List<WidgetPlaylists.Entry>,
            covers: List<Bitmap?>,
            widthDp: Int,
            heightDp: Int,
            density: Float,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, shape.layout)
            // Drawn at a size the launcher can hold: widgets share a bitmap
            // budget, and the blur loses nothing to the scaling.
            val scale = minOf(density, MAX_PANEL_PX / maxOf(widthDp, heightDp).toFloat())
            val panel = WidgetGlass.panel(
                cover, (widthDp * scale).toInt(), (heightDp * scale).toInt(), CORNER_DP * scale, scale,
            )
            views.setImageViewBitmap(R.id.widget_glass, panel)

            views.setTextViewText(R.id.widget_title, now?.title ?: context.getString(R.string.widget_nothing_playing))
            views.setTextViewText(R.id.widget_artist, now?.artist.orEmpty())
            views.setViewVisibility(R.id.widget_artist, if (now?.artist.isNullOrBlank()) View.GONE else View.VISIBLE)
            if (cover != null) {
                views.setImageViewBitmap(R.id.widget_cover, WidgetGlass.rounded(cover, (64 * density).toInt(), 11 * density))
            } else {
                views.setImageViewResource(R.id.widget_cover, R.drawable.widget_cover_placeholder)
            }
            val playing = now?.playing == true
            views.setImageViewResource(R.id.widget_toggle, if (playing) R.drawable.ic_pip_pause else R.drawable.ic_pip_play)
            views.setContentDescription(R.id.widget_toggle, context.getString(if (playing) R.string.pause else R.string.play))

            views.setOnClickPendingIntent(R.id.widget_logo, openApp(context, player = false))
            views.setOnClickPendingIntent(R.id.widget_now, openApp(context, player = true))
            views.setOnClickPendingIntent(R.id.widget_toggle, control(context, ACTION_TOGGLE))
            views.setOnClickPendingIntent(R.id.widget_next, control(context, ACTION_NEXT))
            if (shape != Shape.COMPACT) {
                views.setOnClickPendingIntent(R.id.widget_previous, control(context, ACTION_PREVIOUS))
            } else {
                // The mark only where it leaves the title room to be read.
                views.setViewVisibility(R.id.widget_logo, if (widthDp >= COMPACT_LOGO_WIDTH) View.VISIBLE else View.GONE)
            }

            if (shape == Shape.LARGE || shape == Shape.COLUMN) {
                // As many as fit side by side at a readable size, and none
                // where the song and its controls have taken the height.
                val room = shape == Shape.LARGE || heightDp >= COLUMN_PLAYLISTS_HEIGHT
                val slots = ((widthDp - 28) / PLAYLIST_TILE_DP).coerceIn(2, PLAYLIST_SLOTS)
                val shown = if (room) playlists.take(slots) else emptyList()
                views.setViewVisibility(R.id.widget_playlists, if (shown.isEmpty()) View.GONE else View.VISIBLE)
                SLOTS.forEachIndexed { index, slot ->
                    val entry = shown.getOrNull(index)
                    if (entry == null) {
                        views.setViewVisibility(slot, if (index < slots) View.INVISIBLE else View.GONE)
                        return@forEachIndexed
                    }
                    views.setViewVisibility(slot, View.VISIBLE)
                    val size = (64 * density).toInt()
                    val art = covers.getOrNull(index)
                    views.setImageViewBitmap(
                        slot,
                        if (art != null) WidgetGlass.rounded(art, size, 12 * density)
                        else WidgetGlass.nameTile(entry.name, size, 12 * density, density),
                    )
                    views.setContentDescription(slot, entry.name)
                    views.setOnClickPendingIntent(slot, openPlaylist(context, index, entry))
                }
            }
            return views
        }

        private fun openApp(context: Context, player: Boolean): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(if (player) ACTION_OPEN_PLAYER else Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(
                context, if (player) 1 else 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun openPlaylist(context: Context, index: Int, entry: WidgetPlaylists.Entry): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_PLAYLIST)
                .putExtra(EXTRA_URI, entry.uri)
                .putExtra(EXTRA_NAME, entry.name)
                .putExtra(EXTRA_ARTWORK, entry.artworkUrl)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(
                context, 10 + index, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun control(context: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                context, action.hashCode(),
                Intent(context, SquareWidget::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private val SLOTS = intArrayOf(R.id.widget_playlist_0, R.id.widget_playlist_1, R.id.widget_playlist_2, R.id.widget_playlist_3)

        /** Narrower than this, a widget tall enough for it is the square. */
        private const val TILE_WIDTH = 220
        private const val TILE_MIN_HEIGHT = 110

        /** Below this height the widget is one row. */
        private const val TALL_HEIGHT = 150
        /** What a title beside all three controls needs to be read. */
        private const val INLINE_WIDTH = 320
        /** A narrow column has room for the full-size song and controls from here. */
        private const val COLUMN_HEIGHT = 200
        /** A narrow column has room for playlists under its controls from here. */
        private const val COLUMN_PLAYLISTS_HEIGHT = 250
        private const val COMPACT_LOGO_WIDTH = 230
        /** A playlist tile and the gap after it. */
        private const val PLAYLIST_TILE_DP = 62
        private const val CORNER_DP = 26f
        private const val MAX_PANEL_PX = 600f
    }
}
