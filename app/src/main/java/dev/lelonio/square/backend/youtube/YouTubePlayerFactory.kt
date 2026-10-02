package dev.lelonio.square.backend.youtube

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.lelonio.square.backend.PlaybackHost
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Builds the ExoPlayer the YouTube Music backend plays through.
 *
 * A real ExoPlayer, unlike the Spotify side: there is no engine to hand audio
 * to, the tracks are ordinary HTTPS files, and the queue, gapless transitions
 * and buffering are all things ExoPlayer already does properly.
 */
@UnstableApi
object YouTubePlayerFactory {

    fun create(host: PlaybackHost): Player {
        fun loadControl() = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 500,
                /* bufferForPlaybackAfterRebufferMs = */ 1_500,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val crossfadeStore = (host.context.applicationContext as? dev.lelonio.square.SquareApplication)?.crossfade

        // One speaker for both players; see PcmMixer.
        val mixer = PcmMixer()
        val mainSlot = MixerSlot()
        val tailSlot = MixerSlot(follows = mainSlot, initialFade = 0f).apply { held = true }

        val dataSources = ResolvingDataSource.Factory(
            // Files as well as the network: a kept song resolves to one on the
            // phone, which an HTTP source cannot open.
            androidx.media3.datasource.DefaultDataSource.Factory(
                host.context,
                DefaultHttpDataSource.Factory(),
            ),
            YouTubeStreamResolver(host.context.applicationContext),
        )

        fun buildPlayer(handleFocus: Boolean, slot: MixerSlot) =
            ExoPlayer.Builder(host.context, renderers(host, MixerOutputProvider(host.context, mixer, slot)))
            .setMediaSourceFactory(VideoAwareSourceFactory(DefaultMediaSourceFactory(dataSources), dataSources))
            .setLoadControl(loadControl())
            .setHandleAudioBecomingNoisy(true)
            // Ducks and pauses for whatever else wants the speaker. The Spotify
            // path gets this from its own output; ExoPlayer will not ask for focus
            // at all unless it is told to handle it.
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ handleFocus,
            )
            .setLooper(host.looper)
            .build()

        return buildPlayer(true, mainSlot).let { exo ->
                exo.addAnalyticsListener(Diagnostics)
                // Video for a video, sound for a song, as the queue moves.
                exo.addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                        YouTubeVideoMode.onItem(mediaItem?.mediaId)
                        // The next one asked for now, while this one plays.
                        val next = exo.nextMediaItemIndex
                        if (next >= 0) {
                            exo.getMediaItemAt(next).mediaId
                                .takeIf { it.startsWith(YouTubeBackend.URI_SCHEME) }
                                ?.let(YouTubeStreams::prefetch)
                        }
                    }
                })
                val fades = YouTubeFadeController(exo, crossfadeStore, mixer, mainSlot, tailSlot) {
                    buildPlayer(false, tailSlot)
                }
                val preferences = (host.context.applicationContext as? dev.lelonio.square.SquareApplication)
                    ?.preferences
                // The talking and the sketches around a music video; see SponsorBlock.
                SponsorBlock(exo) { preferences?.sponsorBlock?.value ?: true }
                // Every change of song the listener asks for goes through the
                // fade; see SkipFadePlayer.
                SkipFadePlayer(exo, fades) { preferences?.skipFadeMs() ?: 0 }
            }
    }

    /** Temporary: chasing a stall where the position stops with the sink idle. */
    private object Diagnostics : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onAudioUnderrun(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long,
        ) {
            android.util.Log.w(TAG, "underrun size=$bufferSize sinceFeed=${elapsedSinceLastFeedMs}ms")
        }

        override fun onPlaybackStateChanged(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            state: Int,
        ) {
            android.util.Log.w(TAG, "state=$state pos=${eventTime.currentPlaybackPositionMs}")
        }

        override fun onPlayWhenReadyChanged(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            playWhenReady: Boolean,
            reason: Int,
        ) {
            android.util.Log.w(TAG, "playWhenReady=$playWhenReady reason=$reason")
        }

        override fun onPlaybackParametersChanged(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            parameters: androidx.media3.common.PlaybackParameters,
        ) {
            android.util.Log.w(TAG, "params speed=${parameters.speed} pitch=${parameters.pitch}")
        }

        override fun onAudioSinkError(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            audioSinkError: Exception,
        ) {
            android.util.Log.e(TAG, "sink error", audioSinkError)
        }

        private const val TAG = "SquareYT"
    }

    /**
     * Renderers whose audio sink resamples in software.
     *
     * ExoPlayer would otherwise hand a non-unity speed straight to `AudioTrack`,
     * and on this hardware that track stops consuming: the session stays
     * PLAYING and its buffer keeps filling while the position stands still, so
     * the sound goes and the progress bar jumps back to where it stalled.
     * Sonic — the same resampler Media3 uses when the platform cannot oblige —
     * has no such trouble, and it is what the Spotify path already effectively
     * does through its own output.
     */
    private fun renderers(
        host: PlaybackHost,
        output: androidx.media3.exoplayer.audio.AudioOutputProvider,
    ) =
        object : androidx.media3.exoplayer.DefaultRenderersFactory(host.context) {
            // Video stays on the phone's hardware decoders: FFmpeg's are
            // software, and a 1080p picture through them falls behind and stalls.
            override fun buildVideoRenderers(
                context: android.content.Context,
                extensionRendererMode: Int,
                mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: android.os.Handler,
                eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>,
            ) = super.buildVideoRenderers(
                context,
                EXTENSION_RENDERER_MODE_OFF,
                mediaCodecSelector,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                allowedVideoJoiningTimeMs,
                out,
            )

            init {
                // FFmpeg ahead of the phone's own decoders. Releasing a platform
                // audio codec while another song is starting aborts the whole
                // app on this hardware, inside MediaCodec's metrics
                // (`ubsan: sub-overflow` in updateMediametrics); a decoder of the
                // app's own has no such code to fall into. The platform ones stay
                // behind it for whatever FFmpeg cannot decode.
                setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
            }

            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ) = androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(false)
                .setAudioOutputProvider(output)
                // Karaoke, ahead of Sonic. The default chain was all this sink
                // had, so the dial moved and the voice stayed: the removal is a
                // processor of this app's, and a chain that does not list it
                // does not run it. Before the stretcher, because it works on
                // what the two channels share and a stretched pair shares less.
                .setAudioProcessorChain(
                    // Every stream at the mixer's rate, so a 44.1 kHz song and
                    // a 48 kHz one can be summed; see MixerRateProcessor.
                    androidx.media3.exoplayer.audio.DefaultAudioSink.DefaultAudioProcessorChain(
                        dev.lelonio.square.playback.VocalAudioProcessor(),
                        MixerRateProcessor(),
                    ),
                )
                .build()
        }
}

/**
 * Turns a `ytmusic:track:` URI into the URL the audio really lives at.
 *
 * Resolved at load time rather than when the queue is built, and this is the
 * whole reason for the indirection: a YouTube stream URL is signed and expires
 * within hours, so a queue holding resolved URLs would be a queue that stops
 * working while it sits there. The URI keeps its meaning; only the resolution
 * is perishable.
 *
 * Runs on ExoPlayer's loading thread, so blocking here is correct.
 */
@UnstableApi
private class YouTubeStreamResolver(
    private val context: android.content.Context,
) : ResolvingDataSource.Resolver {

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri.toString()
        if (!uri.startsWith(YouTubeBackend.TRACK_PREFIX) && !uri.startsWith(YouTubeBackend.VIDEO_PREFIX)) {
            return dataSpec
        }
        // "#video" and "#audio" are the two halves of a video played in high
        // quality; see VideoAwareSourceFactory.
        val part = dataSpec.uri.fragment
        val base = uri.substringBefore('#')
        val video = part == null && YouTubeVideoMode.wantsVideo(base)

        // A song kept on the phone plays from the phone. Not while the video is
        // showing and there is a network to fetch it over: the file is the
        // sound alone.
        val kept = dev.lelonio.square.download.YouTubeDownloads.fileFor(context, base)
        // Never for half of a video: the picture is the official video's, and
        // its sound has to be that video's too or the two drift apart.
        if (kept != null && part == null &&
            (!video || dev.lelonio.square.playback.OfflineMode.active.value)
        ) {
            return dataSpec.withUri(android.net.Uri.fromFile(kept))
        }

        // NewPipe holds its downloader in a static, and every extractor reads it
        // at construction: without this the first resolve dies on "downloader is
        // null". Done here rather than at startup so a session that never plays
        // a YouTube track never builds one.
        YouTubeStreams.ensureNewPipe()

        val songId = YouTubeBackend.videoIdOfUri(base)
        // A song watched rather than heard is its official video, sound and
        // all; see YouTubeStreams.watchSource. A video is already itself.
        val watching = part != null || video
        val (videoId, info) = if (watching && base.startsWith(YouTubeBackend.TRACK_PREFIX)) {
            YouTubeStreams.watchSource(songId)
        } else {
            songId to YouTubeStreams.info(songId)
        }

        // The picture alone, as sharp as is worth it on a phone: H.264 first,
        // which every phone decodes in hardware, then whatever else there is.
        if (part == PART_VIDEO) {
            val picture = info.videoOnlyStreams
                .filter { !it.content.isNullOrEmpty() && it.height in 1..MAX_VIDEO_HEIGHT }
                .sortedWith(
                    compareByDescending<org.schabi.newpipe.extractor.stream.VideoStream> { it.height }
                        .thenByDescending { it.format == org.schabi.newpipe.extractor.MediaFormat.MPEG_4 },
                )
                .firstOrNull()?.content
                ?: info.videoStreams.filter { !it.content.isNullOrEmpty() }.maxByOrNull { it.height }?.content
                ?: error("nessun video per $videoId")
            return dataSpec.withUri(android.net.Uri.parse(picture))
        }

        // With the video showing, one of YouTube's muxed streams: those are the
        // only ones carrying picture and sound in a single file, and a single
        // file is what a progressive source can play. They top out at 720p,
        // which is the price of not building a DASH manifest here.
        val url = if (video && part == null) {
            info.videoStreams
                .filter { !it.content.isNullOrEmpty() }
                .maxByOrNull { it.height }
                ?.content
        } else {
            null
        }
        // Audio-only otherwise, best bitrate: a muxed stream would carry a video
        // track nobody is going to look at, over a connection that is often the
        // scarce resource. Also the fallback when a track has no muxed stream.
            // AAC before Opus, even at a lower rate: the app decodes AAC itself
            // (FFmpeg, see renderers), while Opus goes to the platform decoder,
            // whose release aborts the app on this hardware.
            ?: info.audioStreams
                .filter { !it.content.isNullOrEmpty() }
                .sortedWith(
                    compareByDescending<org.schabi.newpipe.extractor.stream.AudioStream> {
                        it.format == org.schabi.newpipe.extractor.MediaFormat.M4A
                    }.thenByDescending { it.averageBitrate },
                )
                .firstOrNull()
                ?.content
            ?: error("nessuna traccia audio per $videoId")

        return dataSpec.withUri(android.net.Uri.parse(url))
    }

}

/**
 * Plays a video as two sources side by side: the picture alone and the sound
 * alone. YouTube serves anything sharper than 360p only that way; the file that
 * carries both is the small one.
 *
 * Decided when the item is added, so it applies to videos (which always want
 * their picture) and to songs added while the video button is on.
 */
@UnstableApi
private class VideoAwareSourceFactory(
    private val base: DefaultMediaSourceFactory,
    dataSources: androidx.media3.datasource.DataSource.Factory,
) : androidx.media3.exoplayer.source.MediaSource.Factory by base {

    private val progressive = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(dataSources)

    override fun createMediaSource(mediaItem: androidx.media3.common.MediaItem): androidx.media3.exoplayer.source.MediaSource {
        val uri = mediaItem.localConfiguration?.uri?.toString() ?: return base.createMediaSource(mediaItem)
        val ours = uri.startsWith(YouTubeBackend.TRACK_PREFIX) || uri.startsWith(YouTubeBackend.VIDEO_PREFIX)
        if (!ours || !YouTubeVideoMode.wantsVideo(uri)) return base.createMediaSource(mediaItem)
        fun half(part: String) = progressive.createMediaSource(
            mediaItem.buildUpon().setUri("$uri#$part").build(),
        )
        return androidx.media3.exoplayer.source.MergingMediaSource(half(PART_VIDEO), half(PART_AUDIO))
    }
}

private const val PART_VIDEO = "video"
private const val PART_AUDIO = "audio"

/** Past this a phone screen shows no difference, and the data plan does. */
private const val MAX_VIDEO_HEIGHT = 1080

/** NewPipe's one-time setup, shared by the player and the downloads. */
object YouTubeStreams {
    const val WATCH_URL = "https://www.youtube.com/watch?v="

    private val infos = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, StreamInfo>>()
    private val watched = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Pair<String, StreamInfo>>>()
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** One fetch per key at a time: the two halves of a video ask at once. */
    private fun lockFor(key: String): Any = locks.computeIfAbsent(key) { Any() }

    /**
     * What YouTube says about a video, kept for a while: the two halves of a
     * video ask within milliseconds of each other, and the URLs stay good for
     * hours.
     */
    fun info(videoId: String): StreamInfo = synchronized(lockFor(videoId)) {
        val now = android.os.SystemClock.elapsedRealtime()
        infos[videoId]?.takeIf { now - it.first < INFO_TTL_MS }?.let { return it.second }
        ensureNewPipe()
        val fresh = StreamInfo.getInfo(ServiceList.YouTube, "$WATCH_URL$videoId")
        infos[videoId] = now to fresh
        if (infos.size > 64) infos.entries.minByOrNull { it.value.first }?.let { infos.remove(it.key) }
        fresh
    }

    /**
     * What to show for a song when it is watched: its official video where
     * there is one that can be played, the song's own upload otherwise. Decided
     * once per song and kept, so the picture and the sound — asked for
     * separately — always come from the same upload. A video that answers
     * "age-restricted" cannot be watched without an account, and is passed over.
     */
    fun watchSource(songId: String): Pair<String, StreamInfo> = synchronized(lockFor("watch:$songId")) {
        val now = android.os.SystemClock.elapsedRealtime()
        watched[songId]?.takeIf { now - it.first < INFO_TTL_MS }?.let { return it.second }
        val song = info(songId)
        val chosen = runCatching { OfficialVideos.forSong(songId, song) }.getOrNull()
            ?.let { id ->
                runCatching { id to info(id) }
                    .onFailure { android.util.Log.i("SquareYTVideo", "official video $id unplayable: ${it.message}") }
                    .getOrNull()
            }
            ?: (songId to song)
        watched[songId] = now to chosen
        chosen
    }

    private val prefetcher = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "SquareYTPrefetch").apply { isDaemon = true }
    }

    /** Asks ahead for what [uri] will need, so the next item starts at once. */
    fun prefetch(uri: String) {
        val id = YouTubeBackend.videoIdOfUri(uri)
        prefetcher.execute {
            runCatching {
                if (uri.startsWith(YouTubeBackend.TRACK_PREFIX) && YouTubeVideoMode.wantsVideo(uri)) {
                    watchSource(id)
                } else {
                    info(id)
                }
            }
        }
    }

    private const val INFO_TTL_MS = 60 * 60 * 1000L

    @Volatile
    private var initialised = false

    /** Idempotent: several tracks resolve on the same thread pool. */
    @Synchronized
    fun ensureNewPipe() {
        if (initialised) return
        NewPipe.init(NewPipeDownloader.create(), Localization.DEFAULT)
        initialised = true
    }
}
