package dev.lelonio.square.backend.spotify

import android.content.Context
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.lelonio.square.nativecore.NativeBridge
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Spotify played the way its web player plays it, for the accounts whose
 * audio keys Spotify refuses (#35).
 *
 * The engine asks for each song's key over a channel Spotify closes to some
 * accounts, and then there is nothing to decrypt the song with. The web player
 * never uses that channel: it plays the MP4 version of the song, protected with
 * Widevine, with a licence from spclient. Android has Widevine, the licence is
 * granted to the token the engine already holds, and ExoPlayer plays the file.
 *
 * Two things learnt on a real phone shape this:
 * - Widevine at L3, the software level browsers use. At L1 the decoder was
 *   handed undecryptable data after every seek, and played silence.
 * - A song starts from its beginning and is then moved to where it should be:
 *   started straight at a position, it decoded to silence even at L3. See
 *   [StartThenSeek].
 *
 * What this cannot do: Spotify Connect (the phone is not a Connect device
 * while this plays), the engine's downloads, and 320 kbps (the web player's
 * best is 256).
 */
@UnstableApi
object SpotifyWebPlayback {

    private const val SPCLIENT = "https://spclient.wg.spotify.com"
    private const val LICENCE = "$SPCLIENT/widevine-license/v1/audio/license"
    private const val TAG = "SquareWebPlayback"
    private const val TRACK_PREFIX = "spotify:track:"

    private val http = OkHttpClient()

    private val _accountRefused = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Said by the engine's player when Spotify refuses this account every key. */
    val accountRefused: SharedFlow<Unit> = _accountRefused.asSharedFlow()

    fun reportAccountRefused() {
        _accountRefused.tryEmit(Unit)
    }

    /** Whether Spotify has refused this account its keys; remembered by LibrespotPlayer. */
    fun needed(context: Context): Boolean =
        context.getSharedPreferences("square_keys", Context.MODE_PRIVATE)
            .getBoolean("account_refused", false)

    fun create(context: Context, looper: Looper, renderers: RenderersFactory): Player {
        val drm = DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID) { uuid: UUID ->
                FrameworkMediaDrm.newInstance(uuid).apply {
                    // setPropertyString("securityLevel", "L3")
                    android.util.Log.i(TAG, "widevine level now ${getPropertyString("securityLevel")}")
                }
            }
            // One session per song, so the next can be readied while this plays.
            .setMultiSession(false)
            .build(Licence())
        val sources = DefaultMediaSourceFactory(
            ResolvingDataSource.Factory(
                DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory()),
                Resolver(),
            ),
        ).setDrmSessionManagerProvider { drm }
        // Temporary: the decoded signal's peak, every second, to see when it goes to zero.
        val meter = object : androidx.media3.exoplayer.audio.TeeAudioProcessor.AudioBufferSink {
            @Volatile var peak = 0
            override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
                android.util.Log.i(TAG, "pcm $sampleRateHz Hz $channelCount ch enc $encoding")
            }
            override fun handleBuffer(buffer: java.nio.ByteBuffer) {
                val shorts = buffer.duplicate().order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                var p = peak
                while (shorts.hasRemaining()) { val v = kotlin.math.abs(shorts.get().toInt()); if (v > p) p = v }
                peak = p
            }
        }
        val metered = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
            override fun buildAudioSink(c: Context, f: Boolean, p: Boolean) =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(c)
                    .setAudioProcessors(arrayOf(androidx.media3.exoplayer.audio.TeeAudioProcessor(meter)))
                    .build()
        }
        val exo = ExoPlayer.Builder(context, metered)
            .setLooper(looper)
            .setMediaSourceFactory(sources)
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .build()
        // Temporary: what the DRM and the decoder do, while this is being made to work.
        exo.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onDrmKeysLoaded(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime) {
                android.util.Log.i(TAG, "keys loaded")
            }
            override fun onDrmSessionAcquired(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, state: Int) {
                android.util.Log.i(TAG, "drm session acquired, state $state")
            }
            override fun onDrmSessionManagerError(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, error: Exception) {
                android.util.Log.w(TAG, "drm error", error)
            }
            override fun onAudioCodecError(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, audioCodecError: Exception) {
                android.util.Log.w(TAG, "codec error", audioCodecError)
            }
            override fun onAudioDecoderInitialized(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                android.util.Log.i(TAG, "decoder $decoderName")
            }
            override fun onPlayerError(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, error: androidx.media3.common.PlaybackException) {
                android.util.Log.w(TAG, "player error ${error.errorCodeName}", error)
            }
        })
        val tick = android.os.Handler(looper)
        tick.post(object : Runnable {
            override fun run() {
                if (exo.isPlaying) android.util.Log.i(TAG, "t pos=${exo.currentPosition} peak=${meter.peak}")
                meter.peak = 0
                tick.postDelayed(this, 1_000)
            }
        })
        exo.volume = 0f
        return StartThenSeek(exo)
    }

    /** The licence, asked with whatever token the engine holds at that moment. */
    private class Licence : MediaDrmCallback {
        private val http = DefaultHttpDataSource.Factory()

        override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): MediaDrmCallback.Response =
            HttpMediaDrmCallback(null, http).executeProvisionRequest(uuid, request)

        override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): MediaDrmCallback.Response {
            val callback = HttpMediaDrmCallback(LICENCE, http)
            NativeBridge.accessToken()?.let { callback.setKeyRequestProperty("Authorization", "Bearer $it") }
            android.util.Log.i(TAG, "licence request type=${request.requestType} url=${request.licenseServerUrl.ifEmpty { "(default)" }} ${request.data.size} bytes")
            return runCatching { callback.executeKeyRequest(uuid, request) }
                .onSuccess { android.util.Log.i(TAG, "licence answer ${it.data.size} bytes") }
                .onFailure { android.util.Log.w(TAG, "licence failed: $it") }
                .getOrThrow()
        }
    }

    /**
     * Turns `spotify:track:` into the address of its MP4 file, at load time:
     * the address is signed and expires, so the queue keeps the song's name.
     * Runs on ExoPlayer's loading thread, so blocking is correct.
     */
    private class Resolver : ResolvingDataSource.Resolver {
        private val resolved = ConcurrentHashMap<String, Pair<Long, String>>()

        override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
            val uri = dataSpec.uri.toString()
            if (!uri.startsWith(TRACK_PREFIX)) return dataSpec
            val id = uri.removePrefix(TRACK_PREFIX)
            val now = android.os.SystemClock.elapsedRealtime()
            val cdn = resolved[id]?.takeIf { now - it.first < CDN_TTL_MS }?.second
                ?: cdnFor(id).also { resolved[id] = now to it }
            return dataSpec.withUri(android.net.Uri.parse(cdn))
        }

        private fun cdnFor(id: String): String {
            val token = NativeBridge.accessToken() ?: error("the engine has no token")
            // The web player's own manifest is where the MP4 files are listed;
            // the track metadata no longer names files at all.
            val media = get(
                "$SPCLIENT/track-playback/v1/media/$TRACK_PREFIX$id" +
                    "?manifestFileFormat=file_ids_mp4&manifestFileFormat=file_ids_mp4_dual",
                token,
            )
            val files = JSONObject(media).optJSONObject("media")?.optJSONObject("$TRACK_PREFIX$id")
                ?.optJSONObject("item")?.optJSONObject("manifest")
                ?.let { it.optJSONArray("file_ids_mp4") ?: it.optJSONArray("file_ids_mp4_dual") }
                ?: error("no MP4 file for $id")
            val all = (0 until files.length()).map { files.getJSONObject(it) }
            // The best the web player has: 256 kbps, then 128.
            val file = all.maxByOrNull { it.optInt("bitrate") } ?: error("no MP4 file for $id")
            val fileId = file.getString("file_id")
            val resolve = get(
                "$SPCLIENT/storage-resolve/v2/files/audio/interactive/11/$fileId" +
                    "?version=10000000&product=9&platform=39&alt=json",
                token,
            )
            return JSONObject(resolve).optJSONArray("cdnurl")?.optString(0)
                ?.takeIf { it.isNotEmpty() } ?: error("no address for $id")
        }

        private fun get(url: String, token: String): String {
            val request = Request.Builder().url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build()
            return http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code} for $url")
                response.body?.string() ?: error("empty answer for $url")
            }
        }
    }

    /**
     * Starts every song at its beginning and moves it where it was asked to be
     * once it is playing. Started straight at a position, the decoder produced
     * silence; moved after its first frames, it plays. The song is kept silent
     * for that moment, so the beginning is not heard on the way past.
     */
    private class StartThenSeek(private val exo: ExoPlayer) : ForwardingPlayer(exo) {

        /** Where the song should be once it has started; unset when nothing is waiting. */
        private var pending = C.TIME_UNSET

        /** The item that has been heard playing, by its uid in the timeline; null for none yet. */
        private var startedUid: Any? = null

        private val handler = android.os.Handler(exo.applicationLooper)

        /** Puts the sound back whatever happened to the seek it was lowered for. */
        private val unmute = Runnable {
            android.util.Log.i(TAG, "unmute skipped while measuring")
        }

        init {
            exo.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    android.util.Log.i(TAG, "item ${mediaItem?.mediaId} (reason $reason)")
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!isPlaying) return
                    startedUid = currentUid()
                    flush()
                }
            })
        }

        private fun currentUid(): Any? =
            if (exo.currentTimeline.isEmpty) null
            else exo.currentTimeline.getPeriod(exo.currentPeriodIndex, androidx.media3.common.Timeline.Period(), true).uid

        private fun hasStarted() = startedUid != null && startedUid == currentUid()

        /** The waiting seek, now that the song is playing: a moment after, while silent. */
        private fun flush() {
            val target = pending
            if (target == C.TIME_UNSET) return
            pending = C.TIME_UNSET
            handler.postDelayed({
                android.util.Log.i(TAG, "seek after start to $target")
                exo.seekTo(target)
                handler.removeCallbacks(unmute)
                handler.postDelayed(unmute, UNMUTE_AFTER_SEEK_MS)
            }, SEEK_AFTER_START_MS)
        }

        private fun later(positionMs: Long) {
            if (positionMs <= 0) {
                pending = C.TIME_UNSET
                return
            }
            pending = positionMs
            exo.volume = 0f
            // Never left silent: whatever becomes of the seek, the sound comes back.
            handler.removeCallbacks(unmute)
            handler.postDelayed(unmute, MAX_SILENCE_MS)
            if (exo.isPlaying) flush()
        }

        override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) {
            startedUid = null
            android.util.Log.i(TAG, "queue of ${mediaItems.size} at $startIndex, $startPositionMs ms")
            super.setMediaItems(mediaItems, startIndex, 0)
            later(startPositionMs)
        }

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            val sameItem = mediaItemIndex == currentMediaItemIndex
            android.util.Log.i(TAG, "seek to $mediaItemIndex/$positionMs (same=$sameItem started=${hasStarted()})")
            if (sameItem && hasStarted()) {
                super.seekTo(mediaItemIndex, positionMs)
            } else {
                super.seekTo(mediaItemIndex, 0)
                later(positionMs)
            }
        }

        override fun seekTo(positionMs: Long) = seekTo(currentMediaItemIndex, positionMs)
    }

    private const val CDN_TTL_MS = 30 * 60 * 1000L
    private const val SEEK_AFTER_START_MS = 400L
    private const val UNMUTE_AFTER_SEEK_MS = 250L
    private const val MAX_SILENCE_MS = 2_000L
}
