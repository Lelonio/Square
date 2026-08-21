package dev.lelonio.square.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.lelonio.square.backend.PlaybackHost
import dev.lelonio.square.data.LocalLibrary

/**
 * The player for music that is already on the phone.
 *
 * Nothing to fetch and nothing to decrypt, so this is ExoPlayer as it comes,
 * reading a file through the content resolver. The only piece of plumbing is
 * the resolver below.
 */
@OptIn(UnstableApi::class)
object LocalPlayerFactory {

    fun create(host: PlaybackHost): Player = ExoPlayer.Builder(host.context, renderers(host))
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                ResolvingDataSource.Factory(
                    DefaultDataSource.Factory(host.context),
                    // The queue carries `local:track:<id>`, which is an
                    // identity rather than a location: it survives being
                    // written to disk and read back next week, where a
                    // `content://` URI is only meaningful while the row it
                    // points at still exists. The location is worked out here,
                    // at the moment of opening the file.
                    ResolvingDataSource.Resolver { spec ->
                        val uri = spec.uri.toString()
                        if (LocalLibrary.isLocal(uri)) {
                            spec.buildUpon().setUri(LocalLibrary.contentUri(uri)).build()
                        } else {
                            spec
                        }
                    },
                ),
            ),
        )
        .setHandleAudioBecomingNoisy(true)
        // Ducks and pauses for whatever else wants the speaker, as the YouTube
        // player does; the Spotify path gets this from its own output.
        .setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setLooper(host.looper)
        .build()

    /**
     * The sink, with the app's own vocoder in its chain.
     *
     * Speed and pitch otherwise fall to the platform's stretcher, which is
     * built for speech and sounds like it on music — and the same song altered
     * the same amount would sound worse from the phone than from Spotify. See
     * BungeeAudioProcessor.
     */
    private fun renderers(host: PlaybackHost) =
        object : androidx.media3.exoplayer.DefaultRenderersFactory(host.context) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ) = androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                // The hardware path applies speed itself, below the processors,
                // which would leave the vocoder with nothing to do and the
                // platform doing it after all.
                .setEnableAudioTrackPlaybackParams(false)
                .setAudioProcessorChain(BungeeProcessorChain())
                .build()
        }
}
