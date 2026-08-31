package dev.lelonio.square.backend.youtube

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.backend.PlaybackHost
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfo

@UnstableApi
object YouTubePlayerFactory {
    fun create(host: PlaybackHost): Player = ExoPlayer.Builder(host.context, renderers(host))
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                ResolvingDataSource.Factory(
                    DefaultHttpDataSource.Factory(),
                    YouTubeStreamResolver(host.context),
                ),
            ),
        )
        .setHandleAudioBecomingNoisy(true)
        .setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true,
        )
        .setLooper(host.looper)
        .build()
        .apply { addAnalyticsListener(Diagnostics) }

    private object Diagnostics : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onAudioUnderrun(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long,
        ) = android.util.Log.w(TAG, "underrun size=$bufferSize sinceFeed=${elapsedSinceLastFeedMs}ms")

        override fun onPlaybackStateChanged(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            state: Int,
        ) = android.util.Log.w(TAG, "state=$state pos=${eventTime.currentPlaybackPositionMs}")

        override fun onPlayWhenReadyChanged(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            playWhenReady: Boolean,
            reason: Int,
        ) = android.util.Log.w(TAG, "playWhenReady=$playWhenReady reason=$reason")

        override fun onPlaybackParametersChanged(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            parameters: androidx.media3.common.PlaybackParameters,
        ) = android.util.Log.w(TAG, "params speed=${parameters.speed} pitch=${parameters.pitch}")

        override fun onAudioSinkError(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            audioSinkError: Exception,
        ) = android.util.Log.e(TAG, "sink error", audioSinkError)

        private const val TAG = "SquareYT"
    }

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

@UnstableApi
internal class YouTubeStreamResolver(private val context: Context) : ResolvingDataSource.Resolver {
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri.toString()
        if (!uri.startsWith(YouTubeBackend.TRACK_PREFIX)) return dataSpec

        val local = (context.applicationContext as? SquareApplication)
            ?.downloadManager
            ?.resolveLocalPathSync(uri)
        if (local != null) return dataSpec.withUri(android.net.Uri.fromFile(local))

        val stream = resolveAudioUrl(uri, Int.MAX_VALUE)
        return dataSpec.withUri(android.net.Uri.parse(stream.url))
    }

    companion object {
        private const val WATCH_URL = "https://www.youtube.com/watch?v="

        fun resolveAudioUrl(trackUri: String, maxBitrateKbps: Int): ResolvedYouTubeStream {
            ensureNewPipe()
            val videoId = YouTubeBackend.videoIdOfUri(trackUri)
            val info = StreamInfo.getInfo(ServiceList.YouTube, "$WATCH_URL$videoId")
            val candidates = info.audioStreams
                .filter { !it.content.isNullOrEmpty() }
                .filter { it.format != null }
                .filter { it.averageBitrate <= 0 || it.averageBitrate <= maxBitrateKbps }
            val selected = (candidates.ifEmpty {
                info.audioStreams.filter { !it.content.isNullOrEmpty() && it.format != null }
            }).maxByOrNull { it.averageBitrate }
                ?: throw IllegalStateException("no supported audio stream for $videoId")
            val format = selected.format ?: throw IllegalStateException("audio stream has no media format")
            return ResolvedYouTubeStream(
                url = selected.content,
                contentType = format.mimeType,
                extension = format.suffix,
            )
        }

        @Volatile
        private var initialised = false

        @Synchronized
        private fun ensureNewPipe() {
            if (initialised) return
            NewPipe.init(NewPipeDownloader.create(), Localization.DEFAULT)
            initialised = true
        }
    }
}

data class ResolvedYouTubeStream(
    val url: String,
    val contentType: String,
    val extension: String,
)
