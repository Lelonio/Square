package dev.lelonio.square.playback.soloist

import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.lelonio.square.playback.AudioEffects
import dev.lelonio.square.ui.EXTRA_ALBUM_URI
import dev.lelonio.square.ui.EXTRA_ARTIST_NAMES
import dev.lelonio.square.ui.EXTRA_ARTIST_URI
import dev.lelonio.square.ui.EXTRA_ARTIST_URIS
import dev.lelonio.square.ui.EXTRA_CONTEXT_ORDERED
import dev.lelonio.square.ui.EXTRA_CONTEXT_URI
import dev.lelonio.square.ui.EXTRA_EXPLICIT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/**
 * Spotify Soloist as this phone's player.
 *
 * Soloist's queue is the queue: what played, what plays and what comes next
 * are read from it (SoloistEngine.State) and shown as they are, the way
 * Spotify's apps show a Connect device's. Square starts a context or a list
 * with a Connect `play`, and everything else is Soloist's own: skips, the
 * songs queued by hand, shuffle and repeat.
 *
 * Until Soloist plays something of this phone's, what is shown is a queue
 * waiting to be sent ([local]): the one put back when the app opens, one just
 * chosen, or the one left here when the music went to another device. Play
 * sends it, at the song and the time shown.
 *
 * Songs cannot be moved or taken out of Soloist's queue; the WebSocket has no
 * way to say it.
 */
@UnstableApi
class SoloistPlayer(
    private val context: Context,
    looper: Looper,
    /** The songs of a context, as queue items; see [adoptAccount]. */
    private val resolve: suspend (contextUri: String?, trackUri: String) -> List<MediaItem>?,
) : SimpleBasePlayer(looper) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = android.os.Handler(Looper.getMainLooper())
    private val output = SoloistAudio.output

    private var state = SoloistEngine.State()

    /** A queue waiting to be sent to Soloist; null when Soloist's is shown. */
    private var local: List<MediaItem>? = null
    private var localIndex = 0
    private var localPosition = 0L

    /** The play that went out, until Soloist names the song it started. */
    private var sentUri: String? = null
    private var sentContext: String? = null
    private var sentSerial = -1
    private var sentAt = 0L
    private var sentPosition = -1L
    private var sentPaused = false

    /**
     * What is known of songs Soloist names without describing (the songs of
     * a list sent from here come back as bare URIs): the items this app sent,
     * and catalogue reads for the rest.
     */
    private val known = mutableMapOf<String, MediaMetadata>()
    private val asked = mutableSetOf<String>()

    /**
     * Soloist's state as it was when a dissolve began, shown until the new
     * song is heard: Soloist names the new song at once and the music is
     * still the old one fading for a second or so.
     */
    private var frozen: SoloistEngine.State? = null
    private val thaw = Runnable { unfreeze() }

    private var wantPlay = false
    private var prepared = false

    /**
     * What a button asked for, shown at once and until Soloist says it too
     * (or a few seconds pass): Soloist's events in between still carry the
     * state from before, and following them made pause flick back to play
     * and the bar jump back before it went where it was sent.
     */
    private var lastItems: List<MediaItem> = emptyList()
    private var lastIndex = 0

    private var heldPlay: Boolean? = null
    private var heldPosition: SoloistEngine.Anchor? = null
    private var heldAt = 0L

    private fun hold(play: Boolean?, positionMs: Long?) {
        val now = System.currentTimeMillis()
        if (play != null) heldPlay = play
        val position = positionMs ?: currentPosition()
        val playing = heldPlay ?: wantPlay
        heldPosition = SoloistEngine.Anchor(position, now, if (playing) parameters.speed.toDouble() else 0.0)
        heldAt = now
    }

    /** Lets go of what was held once Soloist agrees, or after a while regardless. */
    private fun release(next: SoloistEngine.State, was: SoloistEngine.State) {
        if (heldPlay == null && heldPosition == null) return
        val expired = System.currentTimeMillis() - heldAt > HOLD_MS
        if (expired || next.trackSerial != was.trackSerial || local != null) {
            heldPlay = null
            heldPosition = null
            return
        }
        heldPlay?.let { if (next.playing == it && next.status != "buffering") heldPlay = null }
        val held = heldPosition
        val anchor = next.anchor
        if (held != null && anchor != null && anchor != was.anchor && anchor.atMs >= heldAt &&
            kotlin.math.abs(anchor.now() - held.now()) < 1500 && next.status != "buffering"
        ) {
            heldPosition = null
        }
    }
    private var parameters = PlaybackParameters(AudioEffects.speed.value, AudioEffects.pitch.value)

    init {
        check(looper == Looper.getMainLooper())
        SoloistEngine.start(context)
        scope.launch { SoloistEngine.state.collect(::follow) }
        scope.launch { SoloistEngine.restarted.drop(1).collect { if (local != null) send() } }
        scope.launch { SoloistAudio.newSongHeard.drop(1).collect { unfreeze() } }
        bindEffects()
        if (!adoptedAtLaunch) {
            adoptedAtLaunch = true
            scope.launch { adoptAccount() }
        }
    }

    // ------------------------------------------------------------- following

    private fun follow(next: SoloistEngine.State) {
        val was = state
        state = next
        if (!next.running && was.running && local == null) {
            // Soloist stopping (a restart for the quality, or gone): what it
            // was playing is kept here, to be sent again when it is back.
            keepAsLocal(was)
        }
        // Ours by its URI, or another song (a relink) in the context sent: the
        // song Soloist had from before, reported playing a moment after the
        // play went out, is not, and taking it for ours dropped the queue for
        // one that then went empty while the new one loaded.
        val playingOurs = sentUri != null &&
            (next.uri == sentUri || (next.trackSerial != sentSerial && next.uri != null && next.contextUri == sentContext))
        val playingTheirs = sentUri == null && next.status == "playing"
        if (local != null && next.active && next.song != null && (playingOurs || playingTheirs)) {
            // Soloist plays it now: its queue is the one to show.
            local = null
            sentUri = null
            if (pendingFadeIn) {
                pendingFadeIn = false
                output.fadeIn()
            }
        }
        if (local == null && was.active && !next.active && was.song != null) {
            // The account's playback went to another device: the queue stays
            // here, paused where it was, for play to bring it back.
            keepAsLocal(was)
            wantPlay = false
        }
        release(next, was)
        if (was.active != next.active) thread { runCatching { dev.lelonio.square.data.RemoteConnect.refresh() } }
        if (local == null) wantPlay = heldPlay ?: next.playing
        describeUnknown()
        scheduleCrossfade()
        invalidateState()
    }

    /** Soloist's queue as it was, made the queue waiting to be sent. */
    private fun keepAsLocal(from: SoloistEngine.State) {
        val song = from.song ?: return
        val items = from.previous.reversed().map { item(it.song, it.source, from.contextUri) } +
            item(song, "context", from.contextUri) +
            from.upcoming.map { item(it.song, it.source, from.contextUri) }
        local = items
        localIndex = from.previous.size
        localPosition = from.anchor?.now() ?: 0
        sentUri = null
    }

    // ----------------------------------------------------------------- state

    private fun showing(): List<MediaItem> = local ?: soloistItems()

    private fun remember(items: List<MediaItem>) {
        items.forEach { if (!it.mediaMetadata.title.isNullOrEmpty()) known[it.mediaId] = it.mediaMetadata }
    }

    /** Reads the songs Soloist named without describing, once each. */
    private fun describeUnknown() {
        val missing = (listOfNotNull(state.song) + state.previous.map { it.song } + state.upcoming.take(MAX_UPCOMING).map { it.song })
            .filter { it.name.isEmpty() && it.uri !in known && it.uri !in asked && it.uri.startsWith("spotify:track:") }
            .map { it.uri }.distinct()
        if (missing.isEmpty()) return
        asked += missing
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching { dev.lelonio.square.data.Catalog.tracks(missing) }.getOrDefault(emptyList())
            }
            read.forEach { track ->
                known[track.uri] = MediaMetadata.Builder()
                    .setTitle(track.name)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album.takeIf { it.isNotEmpty() })
                    .setDurationMs(track.durationMs.takeIf { it > 0 })
                    .setArtworkUri(track.artworkUrl?.let(android.net.Uri::parse))
                    .setExtras(
                        Bundle().apply {
                            track.artistUri?.let { putString(EXTRA_ARTIST_URI, it) }
                            track.albumUri?.let { putString(EXTRA_ALBUM_URI, it) }
                            if (track.explicit) putBoolean(EXTRA_EXPLICIT, true)
                        },
                    )
                    .build()
            }
            if (read.isNotEmpty()) invalidateState()
        }
    }

    /** The state the player shows: Soloist's, or the one before a dissolve still heard. */
    private fun shown(): SoloistEngine.State = frozen ?: state

    private fun soloistItems(): List<MediaItem> {
        val state = shown()
        val song = state.song ?: return emptyList()
        return state.previous.reversed().map { item(it.song, it.source, state.contextUri) } +
            item(song, "context", state.contextUri) +
            state.upcoming.take(MAX_UPCOMING).map { item(it.song, it.source, state.contextUri) }
    }

    private fun currentIndex(): Int = if (local != null) localIndex else shown().previous.size

    private fun currentPosition(): Long {
        if (local != null) return localPosition
        heldPosition?.let { return it.now() }
        val state = shown()
        return state.anchor?.let { if (state.playing || frozen != null) it.now() else it.positionMs } ?: 0
    }

    private fun freeze() {
        if (local != null || state.song == null || !state.playing) return
        frozen = state
        main.removeCallbacks(thaw)
        // Never longer than libpulse waits for the new song itself.
        main.postDelayed(thaw, 6000)
    }

    private fun unfreeze() {
        main.removeCallbacks(thaw)
        if (frozen == null) return
        frozen = null
        invalidateState()
    }

    override fun getState(): State {
        var items = showing()
        var index = currentIndex()
        // A moment with no song (Soloist between one and the next) is not an
        // empty queue: shown empty, the open player closed itself.
        if (items.isEmpty() && local == null && state.running) {
            items = lastItems
            index = lastIndex
        } else if (items.isNotEmpty()) {
            lastItems = items
            lastIndex = index
        }
        val builder = State.Builder().setAvailableCommands(COMMANDS)
            .setPlayWhenReady(wantPlay, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs { currentPosition() }
            .setPlaybackParameters(parameters)
        if (items.isEmpty()) return builder.setPlaybackState(Player.STATE_IDLE).build()
        val uids = mutableSetOf<String>()
        builder.setPlaylist(items.mapIndexed { i, item ->
            var uid = "${if (local != null) "l" else "s"}$i:${item.mediaId}"
            while (!uids.add(uid)) uid += "'"
            val duration = item.mediaMetadata.durationMs ?: C.TIME_UNSET
            MediaItemData.Builder(uid).setMediaItem(item)
                .setDurationUs(if (duration == C.TIME_UNSET) C.TIME_UNSET else duration * 1_000)
                .setIsSeekable(true)
                .build()
        }).setCurrentMediaItemIndex(index.coerceIn(items.indices))
        val buffering = (local != null && sentUri != null) || (local == null && state.status == "buffering")
        builder.setPlaybackState(if (buffering) Player.STATE_BUFFERING else Player.STATE_READY)
        if (local == null) {
            builder.setShuffleModeEnabled(state.shuffle)
                .setRepeatMode(
                    when (state.repeat) {
                        "track" -> Player.REPEAT_MODE_ONE
                        "context" -> Player.REPEAT_MODE_ALL
                        else -> Player.REPEAT_MODE_OFF
                    },
                )
        }
        return builder.build()
    }

    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()

    // --------------------------------------------------------------- sending

    /**
     * Sends the waiting queue to Soloist, at its song and time: as the
     * context it is when it is one of Spotify's in its own order, so the
     * account shows that context everywhere, and as a list otherwise.
     */
    private fun send() {
        val items = local ?: return
        if (items.isEmpty()) return
        if (!state.loggedIn || SoloistEngine.deviceId == null) {
            SoloistEngine.start(context)
            scope.launch {
                SoloistEngine.state.first { it.loggedIn }
                if (local != null) send()
            }
            return
        }
        val index = localIndex.coerceIn(items.indices)
        val current = items[index]
        val contextUri = current.mediaMetadata.extras?.getString(EXTRA_CONTEXT_URI)
            ?.takeIf { it.startsWith("spotify:") && it != SoloistEngine.LIST_CONTEXT }
        val asContext = contextUri != null && items.all {
            it.mediaMetadata.extras?.getString(EXTRA_CONTEXT_URI) == contextUri &&
                it.mediaMetadata.extras?.getBoolean(EXTRA_CONTEXT_ORDERED) == true
        }
        val position = localPosition
        val paused = !wantPlay
        // The same play again a moment later (the queue set and then play
        // pressed, both asking) is the one already on its way.
        val now = System.currentTimeMillis()
        if (sentUri == current.mediaId && sentPosition == position && sentPaused == paused && now - sentAt < 2000) return
        sentUri = current.mediaId
        sentContext = if (asContext) contextUri else SoloistEngine.LIST_CONTEXT
        sentSerial = state.trackSerial
        sentAt = now
        sentPosition = position
        sentPaused = paused
        scope.launch(Dispatchers.IO) {
            if (asContext) {
                // The whole context, as far as it goes, so the songs before
                // the one picked are there too.
                val from = (index - CONTEXT_PAGE / 2).coerceAtLeast(0)
                val page = items.subList(from, minOf(items.size, from + CONTEXT_PAGE)).map { it.mediaId }
                SoloistEngine.playContext(contextUri!!, page, index - from, position, paused)
            } else {
                // Not under the playlist's name: Soloist opens a context it knows
                // itself and plays it in Spotify's order, so a playlist shown
                // sorted otherwise started its top song at Spotify's end, with
                // nothing after it.
                val from = (index - LIST_BEFORE).coerceAtLeast(0)
                val window = items.subList(from, minOf(items.size, index + LIST_PAGE)).map { it.mediaId }
                SoloistEngine.playList(window, index - from, position, paused, null)
            }
        }
        invalidateState()
    }

    // -------------------------------------------------------------- commands

    override fun handlePrepare(): ListenableFuture<*> {
        prepared = true
        return done()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (!playWhenReady) {
            // A skip's flag still up made libpulse take this pause for a
            // change of song and play the old one on.
            main.removeCallbacks(dropFlag)
            dropFlag.run()
            unfreeze()
            SoloistAudio.pauseNow()
        } else if (local == null && state.song != null) {
            SoloistAudio.resumeNow()
        }
        if (local == null) hold(playWhenReady, null)
        wantPlay = playWhenReady
        if (local != null) {
            if (playWhenReady) send() else if (sentUri != null) SoloistEngine.pause()
        } else if (playWhenReady) {
            SoloistEngine.resume()
        } else {
            SoloistEngine.pause()
        }
        invalidateState()
        return done()
    }

    override fun handleSeek(index: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val target = positionMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
        var mediaItemIndex = index
        frozen?.let { before ->
            // The list on screen is the one from before the dissolve: the
            // song shown next is the one already coming in.
            unfreeze()
            if (index == before.previous.size + 1 && positionMs == C.TIME_UNSET) return done()
            mediaItemIndex = index + (state.previous.size - before.previous.size)
        }
        val waiting = local
        if (waiting != null) {
            if (mediaItemIndex !in waiting.indices) return done()
            localIndex = mediaItemIndex
            localPosition = target
            if (wantPlay || sentUri != null) afterFadeOut { send() }
            invalidateState()
            return done()
        }
        val current = state.previous.size
        when {
            mediaItemIndex == current -> {
                hold(null, target)
                SoloistEngine.seek(target)
                invalidateState()
            }
            mediaItemIndex == current + 1 -> afterFadeOut { SoloistEngine.next() }
            mediaItemIndex == current - 1 -> afterFadeOut { SoloistEngine.previous() }
            mediaItemIndex > current + 1 -> {
                val entry = state.upcoming.getOrNull(mediaItemIndex - current - 1) ?: return done()
                afterFadeOut { scope.launch(Dispatchers.IO) { SoloistEngine.skipTo(entry) } }
            }
            else -> {
                // Further back than one song: the list from there, sent again.
                keepAsLocal(state)
                localIndex = mediaItemIndex
                localPosition = target
                afterFadeOut { send() }
            }
        }
        return done()
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        remember(mediaItems)
        local = mediaItems
        localIndex = startIndex.takeIf { it != C.INDEX_UNSET }?.coerceIn(0, maxOf(0, mediaItems.lastIndex)) ?: 0
        localPosition = startPositionMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
        sentUri = null
        if (wantPlay) send()
        invalidateState()
        return done()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        val waiting = local
        if (waiting != null && sentUri == null) {
            // Not sent yet: they wait in the list, after the song and before
            // the rest, and go with it.
            val at = (localIndex + 1).coerceAtMost(waiting.size)
            local = waiting.take(at) + mediaItems + waiting.drop(at)
        } else {
            // Soloist's own queue, which is Spotify's: it shows up in the
            // queue it publishes, where Spotify puts it.
            mediaItems.map { it.mediaId }.filter { it.startsWith("spotify:track:") || it.startsWith("spotify:episode:") }
                .forEach(SoloistEngine::addToQueue)
        }
        invalidateState()
        return done()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val waiting = local ?: return editSoloistQueue { next, current ->
            // Only what is still to come: what played and what plays are
            // Soloist's. A removal reaching them is the player being emptied
            // as it is let go (a handover to librespot), not an edit, and sent
            // on it emptied Soloist's queue and left its device refusing plays.
            if (fromIndex <= current) return@editSoloistQueue null
            val from = fromIndex - current - 1
            val to = (toIndex - current - 1).coerceAtMost(next.size)
            if (from >= to) null else next.filterIndexed { i, _ -> i !in from until to }
        }
        if (sentUri != null) return done()
        val kept = waiting.filterIndexed { i, _ -> i !in fromIndex until toIndex }
        if (localIndex in fromIndex until toIndex) localPosition = 0
        localIndex = when {
            localIndex >= toIndex -> localIndex - (toIndex - fromIndex)
            localIndex >= fromIndex -> fromIndex
            else -> localIndex
        }.coerceIn(0, maxOf(0, kept.lastIndex))
        local = kept
        invalidateState()
        return done()
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        val waiting = local?.toMutableList() ?: return editSoloistQueue { next, current ->
            val from = fromIndex - current - 1
            val to = toIndex - current - 1
            val at = newIndex - current - 1
            if (from < 0 || to > next.size || at < 0 || from >= to) {
                null
            } else {
                val edited = next.toMutableList()
                val moved = edited.subList(from, to).toList()
                edited.subList(from, to).clear()
                edited.addAll(at.coerceAtMost(edited.size), moved)
                edited
            }
        }
        if (sentUri != null) return done()
        val currentItem = waiting.getOrNull(localIndex)
        val moved = waiting.subList(fromIndex, toIndex).toList()
        waiting.subList(fromIndex, toIndex).clear()
        waiting.addAll(newIndex, moved)
        local = waiting
        localIndex = waiting.indexOf(currentItem).coerceAtLeast(0)
        invalidateState()
        return done()
    }

    /**
     * An edit of Soloist's own queue, made in the songs still to come: [edit]
     * gets them and the playing song's index in the list shown, and returns
     * them changed, or null when there is nothing it may change. Shown at
     * once; Soloist is told with a Connect `set_queue`.
     */
    private fun editSoloistQueue(
        edit: (next: List<SoloistEngine.QueueEntry>, current: Int) -> List<SoloistEngine.QueueEntry>?,
    ): ListenableFuture<*> {
        unfreeze()
        val now = state
        val edited = edit(now.upcoming, now.previous.size) ?: return done()
        SoloistEngine.expectQueue(edited)
        state = SoloistEngine.state.value
        invalidateState()
        val previous = now.previous.reversed()
        scope.launch(Dispatchers.IO) { SoloistEngine.setQueue(previous, edited) }
        return done()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        SoloistEngine.setShuffle(shuffleModeEnabled)
        return done()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        SoloistEngine.setRepeat(
            when (repeatMode) {
                Player.REPEAT_MODE_ONE -> "track"
                Player.REPEAT_MODE_ALL -> "context"
                else -> "off"
            },
        )
        return done()
    }

    /**
     * Tempo and pitch, done by Square's output on the way out of Soloist, as
     * for the Spotify engine.
     */
    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        parameters = playbackParameters
        output.setSpeedAndPitch(playbackParameters.speed, playbackParameters.pitch)
        AudioEffects.rememberSpeedAndPitch(playbackParameters.speed, playbackParameters.pitch)
        return done()
    }

    override fun handleStop(): ListenableFuture<*> {
        wantPlay = false
        SoloistEngine.pause()
        return done()
    }

    override fun handleRelease(): ListenableFuture<*> {
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        return done()
    }

    // ----------------------------------------------------------------- fades

    /** A skip faded the music out; it comes back up with the new song. */
    private var pendingFadeIn = false

    /**
     * A change of song asked for by hand. With the skip fade on, libpulse
     * dissolves the old song into the new one (the flag says this cork is a
     * skip); otherwise the output is faded out shortly, so nothing is cut off
     * mid-note.
     */
    private fun afterFadeOut(action: () -> Unit) {
        if (!wantPlay) return action()
        val skip = (context.applicationContext as? dev.lelonio.square.SquareApplication)?.preferences?.skipFadeMs() ?: 0
        if (skip > 0) {
            raiseSkipFlag(skip.toLong())
            return action()
        }
        pendingFadeIn = true
        output.fadeOutThen(SHORT_FADE_MS) { main.post(action) }
    }

    private val dropFlag = Runnable { runCatching { SoloistEngine.skipFlag(context).delete() } }

    /** Tells libpulse the cork Soloist is about to make is a change of song, and its dissolve. */
    private fun raiseSkipFlag(ms: Long, holdTitle: Boolean = false) {
        // Only a song ending into the next one waits to show it: a skip by
        // hand is answered at once, as a button should be.
        if (holdTitle) freeze()
        runCatching { SoloistEngine.skipFlag(context).writeText(ms.toString()) }
        main.removeCallbacks(dropFlag)
        main.postDelayed(dropFlag, 6000)
    }

    private fun crossfadeMs(): Long = dev.lelonio.square.data.CrossfadeStore(context).durationMs().toLong()

    /**
     * The crossfade between songs that end by themselves. Soloist has none it
     * can be told to make, so the next song is skipped to as the old one has
     * the crossfade left to play, and libpulse lays the rest of the old one
     * under it.
     */
    private val crossfade = Runnable {
        val cross = crossfadeMs()
        if (cross <= 0 || local != null || !state.playing || state.upcoming.isEmpty() || state.repeat == "track") return@Runnable
        raiseSkipFlag(cross, holdTitle = true)
        SoloistEngine.next()
    }

    private fun scheduleCrossfade() {
        main.removeCallbacks(crossfade)
        val cross = crossfadeMs()
        val anchor = state.anchor ?: return
        if (cross <= 0 || local != null || !state.playing || state.durationMs <= 0 || state.upcoming.isEmpty()) return
        if (state.repeat == "track") return
        val left = state.durationMs - anchor.now() - cross
        if (left > 0) main.postDelayed(crossfade, left)
    }

    // --------------------------------------------------------------- effects

    private fun bindEffects() {
        scope.launch {
            kotlinx.coroutines.flow.combine(AudioEffects.speed, AudioEffects.pitch) { s, p -> s to p }
                .collect { (speed, pitch) -> output.setSpeedAndPitch(speed, pitch) }
        }
        scope.launch { AudioEffects.reverb.collect(output::setReverbAmount) }
        (context.applicationContext as? dev.lelonio.square.SquareApplication)?.effectQuality?.let { quality ->
            scope.launch {
                quality.quality.collect { output.setLightEffects(it == dev.lelonio.square.data.EffectQuality.Light) }
            }
        }
    }

    // ----------------------------------------------------------------- items

    /** A song of Soloist's as a queue item, with what the app reads from one. */
    private fun item(song: SoloistEngine.Song, source: String, contextUri: String?): MediaItem {
        val fromContext = source == "context" && contextUri != null && contextUri != SoloistEngine.LIST_CONTEXT
        if (song.name.isEmpty()) {
            known[song.uri]?.let { described ->
                val extras = Bundle(described.extras ?: Bundle()).apply {
                    if (fromContext) putString(EXTRA_CONTEXT_URI, contextUri) else remove(EXTRA_CONTEXT_URI)
                    putBoolean(EXTRA_CONTEXT_ORDERED, fromContext)
                }
                return MediaItem.Builder().setMediaId(song.uri).setUri(song.uri)
                    .setMediaMetadata(described.buildUpon().setExtras(extras).build()).build()
            }
        }
        return MediaItem.Builder()
            .setMediaId(song.uri)
            .setUri(song.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.name)
                    .setArtist(song.artists.joinToString(", ") { it.first })
                    .setAlbumTitle(song.album.takeIf { it.isNotEmpty() })
                    .setDurationMs(song.durationMs.takeIf { it > 0 })
                    .setArtworkUri(song.coverUrl?.let(android.net.Uri::parse))
                    .setExtras(
                        Bundle().apply {
                            if (fromContext) putString(EXTRA_CONTEXT_URI, contextUri)
                            putBoolean(EXTRA_CONTEXT_ORDERED, fromContext)
                            song.artists.firstOrNull()?.second?.let { putString(EXTRA_ARTIST_URI, it) }
                            song.albumUri?.let { putString(EXTRA_ALBUM_URI, it) }
                            if (song.explicit) putBoolean(EXTRA_EXPLICIT, true)
                            val credited = song.artists.filter { it.second != null }
                            if (credited.isNotEmpty()) {
                                putStringArrayList(EXTRA_ARTIST_NAMES, ArrayList(credited.map { it.first }))
                                putStringArrayList(EXTRA_ARTIST_URIS, ArrayList(credited.map { it.second!! }))
                            }
                        },
                    )
                    .build(),
            )
            .build()
    }

    // ------------------------------------------------------------- at launch

    /**
     * On opening, the account's own playback rather than this app's last
     * queue, when the account has one and Soloist is not playing it: what the
     * desktop app played is what play here carries on.
     */
    private suspend fun adoptAccount() {
        SoloistEngine.state.first { it.loggedIn }
        var seen: org.json.JSONObject? = null
        for (attempt in 0 until 20) {
            seen = withContext(Dispatchers.IO) {
                runCatching { org.json.JSONObject(dev.lelonio.square.nativecore.NativeBridge.remoteState()) }.getOrNull()
            }?.takeIf { it.optString("uri").isNotEmpty() }
            if (seen != null) break
            kotlinx.coroutines.delay(500)
        }
        val account = seen ?: return
        val uri = account.optString("uri")
        val contextUri = account.optString("contextUri").takeIf { it.startsWith("spotify:") && it != SoloistEngine.LIST_CONTEXT }
        if (state.playing || wantPlay) return
        val shown = showing().getOrNull(currentIndex())?.mediaId
        if (shown == uri) return
        var found = runCatching { resolve(contextUri, uri) }.getOrNull().orEmpty()
        var at = found.indexOfFirst { it.mediaId == uri }
        if (at < 0) {
            found = runCatching { resolve(null, uri) }.getOrNull().orEmpty().take(1) + found
            at = 0
        }
        if (found.isEmpty() || state.playing || wantPlay) return
        remember(found)
        local = found
        localIndex = at
        localPosition = account.optLong("positionMs")
        sentUri = null
        invalidateState()
    }

    companion object {
        /** Whether the account's playback has been looked at since the app started. */
        @Volatile
        private var adoptedAtLaunch = false

        /** Songs sent as a list's pages in one play. */
        private const val LIST_PAGE = 400
        private const val LIST_BEFORE = 100
        private const val CONTEXT_PAGE = 1000
        private const val HOLD_MS = 4000L

        /** Songs of Soloist's queue shown ahead. */
        private const val MAX_UPCOMING = 200

        /** A skip's fade when the listener's own is off: long enough not to click. */
        private const val SHORT_FADE_MS = 120L

        private val COMMANDS = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA, Player.COMMAND_RELEASE,
            Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_SET_SPEED_AND_PITCH,
        ).build()
    }
}
