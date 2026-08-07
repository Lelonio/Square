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

    fun create(host: PlaybackHost): Player = ExoPlayer.Builder(host.context, renderers(host))
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                ResolvingDataSource.Factory(
                    DefaultHttpDataSource.Factory(),
                    YouTubeStreamResolver(),
                ),
            ),
        )
        .setHandleAudioBecomingNoisy(true)
        // Ducks and pauses for whatever else wants the speaker. The Spotify
        // path gets this from its own output; ExoPlayer will not ask for focus
        // at all unless it is told to handle it.
        .setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setLooper(host.looper)
        .build()
        .apply { addAnalyticsListener(Diagnostics) }

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
    private fun renderers(host: PlaybackHost) =
        object : androidx.media3.exoplayer.DefaultRenderersFactory(host.context) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ) = androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(false)
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
private class YouTubeStreamResolver : ResolvingDataSource.Resolver {

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri.toString()
        if (!uri.startsWith(YouTubeBackend.TRACK_PREFIX)) return dataSpec

        // NewPipe holds its downloader in a static, and every extractor reads it
        // at construction: without this the first resolve dies on "downloader is
        // null". Done here rather than at startup so a session that never plays
        // a YouTube track never builds one.
        ensureNewPipe()

        val videoId = YouTubeBackend.videoIdOfUri(uri)
        val info = StreamInfo.getInfo(ServiceList.YouTube, "$WATCH_URL$videoId")

        // With the video showing, one of YouTube's muxed streams: those are the
        // only ones carrying picture and sound in a single file, and a single
        // file is what a progressive source can play. They top out at 720p,
        // which is the price of not building a DASH manifest here.
        val url = if (YouTubeVideoMode.enabled.value) {
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
            ?: info.audioStreams
                .filter { !it.content.isNullOrEmpty() }
                .maxByOrNull { it.averageBitrate }
                ?.content
            ?: error("nessuna traccia audio per $videoId")

        return dataSpec.withUri(android.net.Uri.parse(url))
    }

    private companion object {
        const val WATCH_URL = "https://www.youtube.com/watch?v="

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
}
