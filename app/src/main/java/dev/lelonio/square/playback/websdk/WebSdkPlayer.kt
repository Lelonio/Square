package dev.lelonio.square.playback.websdk

import android.content.Context
import android.os.Looper
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*

/** Media3 adapter: Square owns the queue and UI, the official SDK owns audio. */
@UnstableApi
class WebSdkPlayer(private val context: Context, looper: Looper) : SimpleBasePlayer(looper) {
    private data class Entry(val uid: Long, val item: MediaItem)
    private val engine = WebSdkEngine(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val items = mutableListOf<Entry>()
    private var original: List<Entry>? = null
    private var uid = 0L
    private var index = 0
    private var position = 0L
    private var wantPlay = false
    private var prepared = false
    private var pending = false
    private var awaitingTrack = false
    private var sdk = WebSdkState()
    private var playback = Player.STATE_IDLE
    private var error: PlaybackException? = null
    private var repeat = Player.REPEAT_MODE_OFF
    private var shuffle = false
    private var lastReportedPosition = 0L
    private var chunkEnd = 0
    /**
     * The queue changed since it was last sent to the SDK, so what the SDK plays
     * after this track is no longer to be trusted. Sending it again would restart
     * the current track, so it waits for the next one.
     */
    private var queueStale = false

    init {
        check(looper == Looper.getMainLooper())
        scope.launch { WebSdkRecovery.disconnected.collect { handleStop(); invalidateState() } }
        scope.launch { engine.state.collect { next ->
            sdk = next
            if (items.isEmpty()) return@collect
            if (next.error != null) {
                playback = Player.STATE_IDLE
                pending = false
                awaitingTrack = false
                wantPlay = false
                if (error == null) {
                    engine.command("pause")
                    error = PlaybackException(context.getString(dev.lelonio.square.R.string.web_sdk_play_error),
                        null, PlaybackException.ERROR_CODE_REMOTE_ERROR)
                    if (next.error in setOf("authentication_error", "account_error")) WebSdkRecovery.requestSetup()
                }
            } else if (next.stage == "ready") {
                if (pending && wantPlay) startCurrent()
                else if (!awaitingTrack && playback != Player.STATE_ENDED) playback = Player.STATE_READY
                if (next.uri.isNotBlank()) {
                    val currentUri = items.getOrNull(index)?.item?.mediaId
                    // Consecutive duplicates have the same URI: a position
                    // reset at the end is still a real item transition.
                    val restarted = !next.paused && lastReportedPosition > next.position + 2_500 &&
                        lastReportedPosition >= next.duration - 2_500
                    if (queueStale && wantPlay && !awaitingTrack && playback != Player.STATE_ENDED &&
                        !next.paused && !next.busy && (next.uri != currentUri || restarted)) {
                        // The SDK moved on through the queue it was sent: play
                        // what comes next in the one we have now.
                        lastReportedPosition = 0
                        advance()
                    } else {
                        if (!queueStale && next.uri != currentUri && !awaitingTrack) {
                            val found = (index + 1 until chunkEnd.coerceAtMost(items.size))
                                .firstOrNull { items[it].item.mediaId == next.uri }
                            if (found != null) index = found
                        }
                        if (items.getOrNull(index)?.item?.mediaId == next.uri) {
                            if (!next.paused && !next.busy && awaitingTrack) {
                                // Paused while the track was still on its way:
                                // its arrival does not undo that.
                                if (wantPlay) awaitingTrack = false
                                else { engine.command("pause"); playback = Player.STATE_READY }
                            }
                            if (!awaitingTrack && playback != Player.STATE_ENDED) {
                                if (next.ended && wantPlay && !next.busy) advance()
                                else {
                                    if (!queueStale && restarted && index + 1 < chunkEnd &&
                                        items[index + 1].item.mediaId == next.uri) index++
                                    position = next.position
                                    wantPlay = !next.paused
                                    playback = Player.STATE_READY
                                }
                                lastReportedPosition = next.position
                            }
                        }
                    }
                }
            }
            invalidateState()
        } }
    }
    /** The current track is over: on to whatever the queue and repeat mode say. */
    private fun advance() {
        when {
            repeat == Player.REPEAT_MODE_ONE -> { position = 0; startCurrent() }
            index + 1 < items.size -> { index++; position = 0; startCurrent() }
            repeat == Player.REPEAT_MODE_ALL -> { index = 0; position = 0; startCurrent() }
            else -> { wantPlay = false; playback = Player.STATE_ENDED; engine.command("pause") }
        }
    }
    override fun getState(): State {
        val builder = State.Builder().setAvailableCommands(COMMANDS)
            .setPlaybackState(if (items.isEmpty()) Player.STATE_IDLE else playback)
            .setPlayWhenReady(wantPlay, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(position).setRepeatMode(repeat).setShuffleModeEnabled(shuffle)
            .setPlayerError(error)
        if (items.isNotEmpty()) {
            builder.setPlaylist(items.map { entry ->
                val duration = entry.item.mediaMetadata.durationMs ?: C.TIME_UNSET
                MediaItemData.Builder(entry.uid).setMediaItem(entry.item)
                    .setDurationUs(if (duration == C.TIME_UNSET) C.TIME_UNSET else duration * 1_000)
                    .setIsSeekable(true)
                    .build()
            }).setCurrentMediaItemIndex(index.coerceIn(items.indices))
        }
        return builder.build()
    }
    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()
    private fun startCurrent() {
        if (items.isEmpty()) return
        pending = true
        error = null
        playback = Player.STATE_BUFFERING
        if (!engine.account.configured) {
            wantPlay = false
            playback = Player.STATE_IDLE
            WebSdkRecovery.requestSetup()
        } else if (sdk.deviceId == null) engine.connect()
        else {
            pending = false
            awaitingTrack = true
            queueStale = false
            chunkEnd = if (repeat == Player.REPEAT_MODE_ONE) index + 1 else (index + 100).coerceAtMost(items.size)
            engine.play(items[index].item.mediaId,
                items.subList(index + 1, chunkEnd).map { it.item.mediaId }, position)
        }
        invalidateState()
    }
    override fun handlePrepare(): ListenableFuture<*> {
        prepared = true
        error = null
        if (items.isNotEmpty()) {
            playback = Player.STATE_READY
            if (wantPlay) startCurrent()
        }
        return done()
    }
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady && playback == Player.STATE_ENDED) position = 0
        wantPlay = playWhenReady
        if (playWhenReady) {
            if (sdk.deviceId == null || sdk.uri != items.getOrNull(index)?.item?.mediaId || playback != Player.STATE_READY)
                startCurrent()
            else engine.resume()
        } else {
            // The last report is up to a second old: pause where we are now.
            position = currentPosition.coerceAtLeast(0)
            pending = false
            engine.command("pause")
        }
        return done()
    }
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (mediaItemIndex !in items.indices) return done()
        val changed = index != mediaItemIndex
        index = mediaItemIndex
        position = positionMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
        lastReportedPosition = position
        if (changed) { if (wantPlay) startCurrent() else { awaitingTrack = false; pending = false } }
        // A track still on its way would arrive at the old place: ask again.
        else if (wantPlay && (awaitingTrack || pending)) startCurrent()
        // Not into whatever else the SDK still holds; play starts from here.
        else if (sdk.uri == items[index].item.mediaId) engine.seek(position)
        return done()
    }
    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        engine.command("pause")
        items.clear()
        items.addAll(mediaItems.map { Entry(++uid, it) })
        original = null
        index = startIndex.takeIf { it != C.INDEX_UNSET }?.coerceIn(0, maxOf(0, items.lastIndex)) ?: 0
        position = startPositionMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
        pending = false
        awaitingTrack = false
        error = null
        playback = if (items.isEmpty()) Player.STATE_IDLE else if (prepared) Player.STATE_READY else Player.STATE_IDLE
        if (shuffle) reorder(true)
        queueStale = true
        if (wantPlay) startCurrent()
        return done()
    }
    /**
     * After a queue edit. The playing track is left alone unless the edit changed
     * which one it is.
     */
    private fun queueEdited(previous: Long?) {
        if (items.getOrNull(index)?.uid == previous) {
            queueStale = true
            return
        }
        position = 0
        lastReportedPosition = 0
        if (playback != Player.STATE_IDLE) playback = Player.STATE_READY
        if (wantPlay) startCurrent()
        else {
            pending = false
            awaitingTrack = false
            queueStale = true
            if (sdk.uri == items.getOrNull(index)?.item?.mediaId) engine.seek(0)
        }
    }
    /** Keeps the position where it is through a change that leaves the track playing. */
    private fun holdPosition() { position = currentPosition.coerceAtLeast(0) }
    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        holdPosition()
        val current = items.getOrNull(this.index)?.uid
        items.addAll(index, mediaItems.map { Entry(++uid, it) })
        original = null
        this.index = items.indexOfFirst { it.uid == current }.coerceAtLeast(0)
        queueEdited(current)
        return done()
    }
    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        holdPosition()
        val current = items.getOrNull(index)?.uid
        items.subList(fromIndex, toIndex).clear()
        original = null
        index = items.indexOfFirst { it.uid == current }.takeIf { it >= 0 } ?: fromIndex.coerceAtMost(maxOf(0, items.lastIndex))
        if (items.isEmpty()) { position = 0; handleStop() } else queueEdited(current)
        return done()
    }
    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        holdPosition()
        val current = items.getOrNull(index)?.uid
        val moved = items.subList(fromIndex, toIndex).toList()
        items.subList(fromIndex, toIndex).clear()
        items.addAll(newIndex, moved)
        original = null
        index = items.indexOfFirst { it.uid == current }.coerceAtLeast(0)
        queueEdited(current)
        return done()
    }
    private fun reorder(enabled: Boolean) {
        val current = items.getOrNull(index)
        if (enabled) {
            original = items.toList()
            val rest = items.filter { it.uid != current?.uid }.shuffled()
            items.clear()
            current?.let { items.add(it) }
            items.addAll(rest)
            index = 0
        } else {
            original?.let { items.clear(); items.addAll(it) }
            index = items.indexOfFirst { it.uid == current?.uid }.coerceAtLeast(0)
            original = null
        }
    }
    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        if (shuffle != shuffleModeEnabled) {
            holdPosition()
            shuffle = shuffleModeEnabled
            reorder(shuffle)
            queueStale = true
        }
        return done()
    }
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        if (repeat != repeatMode) {
            holdPosition()
            // Only repeating one changes what was sent to the SDK.
            if (repeat == Player.REPEAT_MODE_ONE || repeatMode == Player.REPEAT_MODE_ONE) queueStale = true
            repeat = repeatMode
        }
        return done()
    }
    override fun handleStop(): ListenableFuture<*> {
        wantPlay = false
        pending = false
        awaitingTrack = false
        queueStale = false
        playback = Player.STATE_IDLE
        engine.stopPlayback()
        return done()
    }
    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        engine.release()
        return done()
    }
    companion object {
        private val COMMANDS = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA, Player.COMMAND_RELEASE, Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SHUFFLE_MODE,
        ).build()
    }
}
