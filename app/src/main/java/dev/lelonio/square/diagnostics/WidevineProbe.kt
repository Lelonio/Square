package dev.lelonio.square.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaDrm
import android.util.Base64
import androidx.core.content.ContextCompat
import dev.lelonio.square.BuildConfig
import dev.lelonio.square.nativecore.NativeBridge
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.math.BigInteger

/**
 * Temporary, dev builds only: whether a Spotify track can be played the way
 * the web player plays it — the MP4 file, Widevine, a licence from spclient —
 * for the accounts whose audio keys are refused (#35).
 *
 * Started from adb, runs on the phone so the account's token never leaves it,
 * and logs only what each step answered:
 *
 *     adb shell am broadcast -a dev.lelonio.square.PROBE_WIDEVINE \
 *         --es track 1mblCC44D31Yu2o3DhrvbJ -p dev.lelonio.square
 */
object WidevineProbe {

    private const val ACTION = "dev.lelonio.square.PROBE_WIDEVINE"
    private const val TAG = "SquareProbe"
    private const val SPCLIENT = "https://spclient.wg.spotify.com"
    private val WIDEVINE = java.util.UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
    private val http = OkHttpClient()

    fun register(context: Context) {
        if (!BuildConfig.VERBOSE_LOG) return
        appContext = context.applicationContext
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    // The web player's client-token, for the licence request; kept in
                    // memory only and never written to the log.
                    clientToken = intent.getStringExtra("clienttoken")
                    intent.getStringExtra("webtoken")?.let { webToken = it.removePrefix("Bearer ").trim() }
                    if (intent.getBooleanExtra("reference", false)) {
                        playReference()
                        return
                    }
                    // Pretends Spotify refuses this account its keys, or stops
                    // pretending: the way to try SpotifyWebPlayback on an account
                    // that is not refused.
                    intent.getStringExtra("web")?.let { mode ->
                        val on = mode == "on"
                        context.getSharedPreferences("square_keys", Context.MODE_PRIVATE)
                            .edit().putBoolean("account_refused", on).apply()
                        dev.lelonio.square.playback.websdk.WebSdkRecovery.refresh(context)
                        log("web playback forced ${if (on) "on" else "off"}")
                        if (on) {
                            forceWeb()
                        }
                        return
                    }
                    val track = intent.getStringExtra("track") ?: "1mblCC44D31Yu2o3DhrvbJ"
                    Thread({ runCatching { probe(track) }.onFailure { log("failed: $it") } }, "SquareProbe").start()
                }
            },
            IntentFilter(ACTION),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun forceWeb() = dev.lelonio.square.backend.spotify.SpotifyWebPlayback.reportAccountRefused()

    private fun log(message: String) { android.util.Log.i(TAG, message) }

    private fun probe(track: String) {
        val token = NativeBridge.accessToken() ?: return log("no token: is the engine signed in?")
        val gid = gidOf(track)
        log("track $track = gid $gid")

        // 1. The files the track comes in.
        val (metaCode, meta) = get("$SPCLIENT/metadata/4/track/$gid?market=from_token", token, json = true)
        log("1 metadata: HTTP $metaCode")
        meta?.let { body ->
            val root = runCatching { JSONObject(body) }.getOrNull()
            log("1 keys: ${root?.keys()?.asSequence()?.toList()}")
            log("1 sample: ${body.take(600)}")
            root?.optJSONArray("file")?.let { log("1 file[0]: ${it.optJSONObject(0)}") }
            log("1 audio_formats: ${root?.opt("audio_formats").toString().take(700)}")
            log("1 original_audio: ${root?.opt("original_audio").toString().take(400)}")
            root?.optJSONArray("alternative")?.let { log("1 alternatives: ${it.length()}") }
        }
        val files = meta?.let { JSONObject(it) }?.let { root ->
            val list = root.optJSONArray("file") ?: root.optJSONArray("alternative")
                ?.optJSONObject(0)?.optJSONArray("file")
            (0 until (list?.length() ?: 0)).map { list!!.getJSONObject(it) }
                .map { it.optString("format") to it.optString("file_id") }
        }.orEmpty()
        log("1 formats in metadata: ${files.joinToString { it.first }}")

        // 1b. Where the web player asks: its media manifest lists the MP4 files.
        val (mediaCode, media) = get(
            "$SPCLIENT/track-playback/v1/media/spotify:track:$track?manifestFileFormat=file_ids_mp4&manifestFileFormat=file_ids_mp4_dual",
            token,
            json = true,
        )
        log("1b media: HTTP $mediaCode, ${media?.take(500)}")
        val mp4Files = media?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optJSONObject("media")?.optJSONObject("spotify:track:$track")
            ?.optJSONObject("item")?.optJSONObject("manifest")
            ?.let { manifest -> manifest.optJSONArray("file_ids_mp4") ?: manifest.optJSONArray("file_ids_mp4_dual") }
        log("1b all MP4 files: ${(0 until (mp4Files?.length() ?: 0)).map { mp4Files!!.optJSONObject(it) }.joinToString { "format=${it.optString("format")} bitrate=${it.optString("bitrate")}" }}")
        val first = (0 until (mp4Files?.length() ?: 0)).map { mp4Files!!.optJSONObject(it) }
            .firstOrNull { it.optString("format") == "11" } ?: mp4Files?.optJSONObject(0)
            ?: return log("1b no MP4 file offered by the media manifest")
        val fileId = first.optString("file_id")
        log("1b chose MP4 file: format=${first.optString("format")} bitrate=${first.optString("bitrate")}")

        // 2. What protects it.
        val (seekCode, seek) = get("https://seektables.scdn.co/seektable/$fileId.json", null, json = true)
        val seektable = seek?.let { runCatching { JSONObject(it) }.getOrNull() }
        val pssh = seektable?.optString("pssh").orEmpty()
        log("2 seektable: HTTP $seekCode, keys=${seektable?.keys()?.asSequence()?.toList()}, pssh=${pssh.length} chars")

        // 3. Where it lives.
        val (resolveCode, resolve) = get(
            "$SPCLIENT/storage-resolve/v2/files/audio/interactive/11/$fileId?version=10000000&product=9&platform=39&alt=json",
            token,
            json = true,
        )
        val cdn = resolve?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optJSONArray("cdnurl")?.optString(0)
        log("3 storage-resolve: HTTP $resolveCode, cdn=${cdn != null}")

        // 4. Whether the file carries its own protection box.
        if (cdn != null) {
            val head = http.newCall(Request.Builder().url(cdn).header("Range", "bytes=0-65535").build()).execute()
            val bytes = head.body?.bytes() ?: ByteArray(0)
            val text = String(bytes, Charsets.ISO_8859_1)
            log(
                "4 file head: HTTP ${head.code}, ${bytes.size} bytes, boxes: " +
                    listOf("ftyp", "moov", "pssh", "sidx", "moof", "tenc").filter { text.contains(it) },
            )
            fun hexAt(name: String, length: Int): String {
                val at = text.indexOf(name)
                if (at < 0) return "absent"
                return bytes.copyOfRange(at + 4, minOf(bytes.size, at + 4 + length)).joinToString("") { "%02x".format(it) }
            }
            val schm = text.indexOf("schm")
            log("4 schm scheme: ${if (schm >= 0) String(bytes, schm + 8, 4, Charsets.ISO_8859_1) else "absent"} raw=${hexAt("schm", 12)}")
            log("4 tenc: ${hexAt("tenc", 32)}")
            log("4 senc: ${hexAt("senc", 24)} saiz: ${hexAt("saiz", 12)} saio: ${hexAt("saio", 12)}")
            log("4 stsd/enca: ${text.contains("enca")} frma: ${hexAt("frma", 4)}")
            // A stretch well past the clear lead: what an encrypted fragment carries.
            val later = http.newCall(Request.Builder().url(cdn).header("Range", "bytes=1000000-1400000").build()).execute()
            val laterBytes = later.body?.bytes() ?: ByteArray(0)
            val laterText = String(laterBytes, Charsets.ISO_8859_1)
            val boxes = listOf("moof", "traf", "tfhd", "trun", "senc", "saiz", "saio", "sbgp", "sgpd", "uuid", "mdat")
                .associateWith { name -> laterText.split(name).size - 1 }
            fun box(src: ByteArray, srcText: String, name: String, max: Int = 80): String {
                val at = srcText.indexOf(name)
                if (at < 0) return "absent"
                val size = java.nio.ByteBuffer.wrap(src, at - 4, 4).int
                return "size=$size " + src.copyOfRange(at - 4, minOf(src.size, at - 4 + minOf(size, max))).joinToString("") { "%02x".format(it) }
            }
            log("4 head sgpd: ${box(bytes, text, "sgpd")}")
            log("4 head sbgp: ${box(bytes, text, "sbgp")}")
            log("4 later sbgp: ${box(laterBytes, laterText, "sbgp")}")
            log("4 later sgpd: ${box(laterBytes, laterText, "sgpd")}")
            log("4 later senc: ${box(laterBytes, laterText, "senc", 64)}")
            log("4 later stretch: HTTP ${later.code}, ${laterBytes.size} bytes, box counts $boxes")
            val moof = laterText.indexOf("moof")
            if (moof >= 0) {
                val size = java.nio.ByteBuffer.wrap(laterBytes, moof - 4, 4).int
                log("4 first moof there: $size bytes: " + laterBytes.copyOfRange(moof - 4, minOf(laterBytes.size, moof - 4 + minOf(size, 200))).joinToString("") { "%02x".format(it) })
            }
        }

        // 5. Whether Spotify gives a licence for it with the token we have.
        if (pssh.isEmpty()) return log("5 skipped: no pssh to ask a licence with")
        val drm = MediaDrm(WIDEVINE)
        log("5 widevine level: ${runCatching { drm.getPropertyString("securityLevel") }.getOrNull()}")
        runCatching { drm.setPropertyString("securityLevel", "L3") }
        log("5 level for licence test: ${runCatching { drm.getPropertyString("securityLevel") }.getOrNull()}")
        drm.setOnKeyStatusChangeListener({ _, _, keys, _ ->
            keys.forEach { key ->
                log("5 key ${key.keyId.joinToString("") { "%02x".format(it) }} status ${key.statusCode}")
            }
        }, android.os.Handler(android.os.Looper.getMainLooper()))
        val session = drm.openSession()
        try {
            val request = drm.getKeyRequest(
                session,
                Base64.decode(pssh, Base64.DEFAULT),
                "audio/mp4",
                MediaDrm.KEY_TYPE_STREAMING,
                null,
            )
            val response = http.newCall(
                Request.Builder()
                    .url("$SPCLIENT/widevine-license/v1/audio/license")
                    .header("Authorization", "Bearer $token")
                    .post(request.data.toRequestBody("application/octet-stream".toMediaType()))
                    .build(),
            ).execute()
            val licence = response.body?.bytes() ?: ByteArray(0)
            log("5 licence: HTTP ${response.code}, ${licence.size} bytes")
            if (response.isSuccessful) {
                runCatching { drm.provideKeyResponse(session, licence) }
                    .onSuccess { log("5 licence accepted by the device: keys loaded"); Thread.sleep(500) }
                    .onFailure { log("5 licence refused by the device: $it") }
            }
        } finally {
            drm.closeSession(session)
            drm.close()
        }

        // 6. Whether ExoPlayer plays it: eight seconds, quietly.
        // 6. The same file as DASH, the way the web player reads it.
        if (cdn != null && seektable != null) playAsDash(cdn, seektable, pssh, token)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun playFor(cdn: String, token: String) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.post {
            // Measures what the decoder hands over, before the speaker: silence
            // here is a decryption that "worked" and produced nothing.
            var peak = 0
            var frames = 0L
            val meter = object : androidx.media3.exoplayer.audio.TeeAudioProcessor.AudioBufferSink {
                override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
                    log("6 pcm: $sampleRateHz Hz, $channelCount ch, encoding $encoding")
                }
                override fun handleBuffer(buffer: java.nio.ByteBuffer) {
                    val shorts = buffer.duplicate().order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                    while (shorts.hasRemaining()) {
                        val v = kotlin.math.abs(shorts.get().toInt())
                        if (v > peak) peak = v
                        frames++
                    }
                }
            }
            val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(appContext!!) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ) = androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(androidx.media3.exoplayer.audio.TeeAudioProcessor(meter)))
                    .build()
            }
            // Widevine L3, the software level Chrome uses: with L1 the keys only
            // decrypt inside the hardware's protected path, and the ordinary
            // audio decoder was handed zeros.
            val drm = androidx.media3.exoplayer.drm.DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(androidx.media3.common.C.WIDEVINE_UUID) { uuid ->
                    androidx.media3.exoplayer.drm.FrameworkMediaDrm.newInstance(uuid).apply {
                        // setPropertyString("securityLevel", "L3")
                    }
                }
                .build(
                    androidx.media3.exoplayer.drm.HttpMediaDrmCallback(
                        "$SPCLIENT/widevine-license/v1/audio/license",
                        androidx.media3.datasource.DefaultHttpDataSource.Factory(),
                    ).apply { setKeyRequestProperty("Authorization", "Bearer $token") },
                )
            val sources = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(appContext!!)
                .setDrmSessionManagerProvider { drm }
            val player = androidx.media3.exoplayer.ExoPlayer.Builder(appContext!!, renderers)
                .setMediaSourceFactory(sources)
                .build()
            player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                override fun onAudioDecoderInitialized(
                    eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) { log("6 decoder: $decoderName") }
                override fun onAudioInputFormatChanged(
                    eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                    format: androidx.media3.common.Format,
                    decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
                ) { log("6 input format: ${format.sampleMimeType} codecs=${format.codecs} ch=${format.channelCount} drm=${format.drmInitData?.schemeType} init=${format.initializationData.map { it.size }}") }
                override fun onDrmKeysLoaded(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime) { log("6 drm keys loaded") }
                override fun onDrmSessionManagerError(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, error: Exception) { log("6 drm error: $error") }
                override fun onAudioCodecError(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, audioCodecError: Exception) { log("6 codec error: $audioCodecError") }
            })
            player.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { log("6 state=$state pos=${player.currentPosition}") }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) { log("6 error: ${error.errorCodeName} ${error.cause}") }
            })
            player.volume = 0f
            player.setMediaItem(
                androidx.media3.common.MediaItem.Builder()
                    .setUri(cdn)
                    .setMimeType(androidx.media3.common.MimeTypes.AUDIO_MP4)
                    .setDrmConfiguration(
                        androidx.media3.common.MediaItem.DrmConfiguration.Builder(androidx.media3.common.C.WIDEVINE_UUID)
                            .setLicenseUri("$SPCLIENT/widevine-license/v1/audio/license")
                            .setLicenseRequestHeaders(mapOf("Authorization" to "Bearer $token"))
                            .build(),
                    )
                    .build(),
            )
            player.prepare()
            player.play()
            main.postDelayed({
                log("6 before seek: peak=$peak over $frames samples, pos=${player.currentPosition}")
                peak = 0
                frames = 0
                player.seekTo(60_000)
            }, 4_000)
            main.postDelayed({
                log("6 after 10 s: playing=${player.isPlaying} pos=${player.currentPosition} format=${player.audioFormat?.sampleMimeType} ${player.audioFormat?.sampleRate} ch=${player.audioFormat?.channelCount}")
                log("6 pcm peak=$peak of 32767 over $frames samples")
                player.release()
            }, 10_000)
        }
    }

    /**
     * Google's own Widevine test stream, to tell this phone's decryption from
     * Spotify's files: if this decodes and Spotify's does not, the files are
     * the difference.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun playReference() {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.post {
            var peak = 0
            val meter = object : androidx.media3.exoplayer.audio.TeeAudioProcessor.AudioBufferSink {
                override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {}
                override fun handleBuffer(buffer: java.nio.ByteBuffer) {
                    val shorts = buffer.duplicate().order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                    while (shorts.hasRemaining()) { val v = kotlin.math.abs(shorts.get().toInt()); if (v > peak) peak = v }
                }
            }
            val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(appContext!!) {
                override fun buildAudioSink(c: Context, f: Boolean, p: Boolean) =
                    androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(c)
                        .setAudioProcessors(arrayOf(androidx.media3.exoplayer.audio.TeeAudioProcessor(meter)))
                        .build()
            }
            val player = androidx.media3.exoplayer.ExoPlayer.Builder(appContext!!, renderers).build()
            player.volume = 0f
            // The audio alone: it is the audio's decryption being compared.
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                .build()
            player.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) { log("R error ${error.errorCodeName} ${error.cause}") }
            })
            player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                override fun onDrmKeysLoaded(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime) { log("R keys loaded") }
                override fun onAudioInputFormatChanged(
                    eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                    format: androidx.media3.common.Format,
                    decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
                ) { log("R audio ${format.codecs} drm=${format.drmInitData?.schemeType}") }
            })
            player.setMediaItem(
                androidx.media3.common.MediaItem.Builder()
                    .setUri("https://storage.googleapis.com/wvmedia/cenc/h264/tears/tears.mpd")
                    .setDrmConfiguration(
                        androidx.media3.common.MediaItem.DrmConfiguration.Builder(androidx.media3.common.C.WIDEVINE_UUID)
                            .setLicenseUri("https://proxy.uat.widevine.com/proxy?video_id=2015_tears&provider=widevine_test")
                            .build(),
                    )
                    .build(),
            )
            player.prepare()
            player.play()
            val tick = object : Runnable {
                var n = 0
                override fun run() {
                    log("R t=${n}s pos=${player.currentPosition} playing=${player.isPlaying} peak=$peak tracks=${player.currentTracks.groups.map { it.type }}")
                    peak = 0
                    if (++n < 22) main.postDelayed(this, 1_000) else player.release()
                }
            }
            main.postDelayed(tick, 1_000)
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun playAsDash(cdn: String, seektable: JSONObject, pssh: String, token: String) {
        val index = seektable.optJSONArray("index_range")
        log("6 seektable offset=${seektable.opt("offset")} index_range=$index timescale=${seektable.opt("timescale")}")
        if (index == null || index.length() < 2) return log("6 no index range")
        val start = index.getLong(0)
        val end = index.getLong(1)
        val url = cdn.replace("&", "&amp;")
        val mpd = """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:cenc="urn:mpeg:cenc:2013" type="static" mediaPresentationDuration="PT600S" minBufferTime="PT2S" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011">
 <Period>
  <AdaptationSet mimeType="audio/mp4" codecs="mp4a.40.2" contentType="audio">
   <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc"/>
   <ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"><cenc:pssh>$pssh</cenc:pssh></ContentProtection>
   <Representation id="a" bandwidth="256000" audioSamplingRate="44100">
    <BaseURL>$url</BaseURL>
    <SegmentBase indexRange="$start-$end"><Initialization range="0-${start - 1}"/></SegmentBase>
   </Representation>
  </AdaptationSet>
 </Period>
</MPD>"""
        val file = java.io.File(appContext!!.cacheDir, "probe.mpd").apply { writeText(mpd) }
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.post {
            var peak = 0
            val meter = object : androidx.media3.exoplayer.audio.TeeAudioProcessor.AudioBufferSink {
                override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {}
                override fun handleBuffer(buffer: java.nio.ByteBuffer) {
                    val shorts = buffer.duplicate().order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                    while (shorts.hasRemaining()) { val v = kotlin.math.abs(shorts.get().toInt()); if (v > peak) peak = v }
                }
            }
            val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(appContext!!) {
                override fun buildAudioSink(c: Context, f: Boolean, p: Boolean) =
                    androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(c)
                        .setAudioProcessors(arrayOf(androidx.media3.exoplayer.audio.TeeAudioProcessor(meter)))
                        .build()
            }
            val player = androidx.media3.exoplayer.ExoPlayer.Builder(appContext!!, renderers).build()
            player.volume = 0f
            player.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) { log("6 error ${error.errorCodeName} ${error.cause}") }
            })
            player.setMediaItem(
                androidx.media3.common.MediaItem.Builder()
                    .setUri(android.net.Uri.fromFile(file))
                    .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MPD)
                    .setDrmConfiguration(
                        androidx.media3.common.MediaItem.DrmConfiguration.Builder(androidx.media3.common.C.WIDEVINE_UUID)
                            .setLicenseUri("$SPCLIENT/widevine-license/v1/audio/license")
                            .setLicenseRequestHeaders(
                                buildMap {
                                    put("Authorization", "Bearer ${webToken ?: token}")
                                    clientToken?.let { put("client-token", it) }
                                },
                            )
                            .build(),
                    )
                    .build(),
            )
            log("6 licence with client-token: ${clientToken != null}, web player token: ${webToken != null}")
            player.prepare()
            player.play()
            val tick = object : Runnable {
                var n = 0
                override fun run() {
                    log("6 dash t=${n}s pos=${player.currentPosition} playing=${player.isPlaying} peak=$peak")
                    peak = 0
                    if (++n < 22) main.postDelayed(this, 1_000) else player.release()
                }
            }
            main.postDelayed(tick, 1_000)
        }
    }

    private var appContext: Context? = null

    @Volatile private var clientToken: String? = null
    @Volatile private var webToken: String? = null

    private fun get(url: String, token: String?, json: Boolean): Pair<Int, String?> {
        val request = Request.Builder().url(url).apply {
            token?.let { header("Authorization", "Bearer $it") }
            if (json) header("Accept", "application/json")
        }.build()
        return http.newCall(request).execute().use { it.code to it.body?.string() }
    }

    /** A track's base62 id as the 32 hex digits the metadata endpoint wants. */
    private fun gidOf(base62: String): String {
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        var value = BigInteger.ZERO
        for (char in base62) value = value.multiply(BigInteger.valueOf(62)).add(BigInteger.valueOf(alphabet.indexOf(char).toLong()))
        return value.toString(16).padStart(32, '0')
    }
}
