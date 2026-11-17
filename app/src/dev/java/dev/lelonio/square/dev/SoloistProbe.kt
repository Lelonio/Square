package dev.lelonio.square.dev

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * Dev-only probe: runs Spotify Soloist inside this app's process tree, under
 * the same sandbox an app has, to see what of it runs and what the platform
 * refuses. Driven from adb:
 *
 *   adb shell am broadcast -n dev.lelonio.square/.dev.SoloistProbe --es args "--version"
 *   adb shell am broadcast -n dev.lelonio.square/.dev.SoloistProbe --es do stop
 *
 * The files are pushed by adb to the app's external files dir, `soloist/`
 * (`soloist`, `lib/` with the glibc runtime, `ca.pem`, optionally `key`), and
 * copied here into the app's own data dir before each run. `@KEY` in the
 * arguments is replaced by the contents of `key`, which is never logged.
 */
class SoloistProbe : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.getStringExtra("do") ?: "run") {
            // As a new install has it: no runtime and no binary, the key and
            // Soloist's own data (its sign-in) kept unless asked.
            "reset" -> {
                dev.lelonio.square.playback.soloist.SoloistEngine.stop()
                val home = dev.lelonio.square.playback.soloist.SoloistRuntime.home(context)
                listOf("lib", "soloist", "ca.pem", "runtime.version").forEach { java.io.File(home, it).deleteRecursively() }
                if (intent.getBooleanExtra("all", false)) home.deleteRecursively()
                context.getSharedPreferences("soloist", android.content.Context.MODE_PRIVATE).edit().clear().apply()
                dev.lelonio.square.playback.soloist.SoloistEngine.setEnabled(context, false)
                android.util.Log.i("SoloistProbe", "reset: ${home.list()?.joinToString()}")
                return
            }
            // As if Soloist had fallen over: killed, not asked to stop.
            "crash" -> {
                val engine = dev.lelonio.square.playback.soloist.SoloistEngine
                val field = engine.javaClass.getDeclaredField("process").apply { isAccessible = true }
                (field.get(engine) as? Process)?.destroyForcibly()
                android.util.Log.i("SoloistProbe", "crashed Soloist")
                return
            }
            "stop" -> {
                running?.destroy()
                running = null
                Log.i(TAG, "stopped")
            }
            // Soloist as the Spotify engine, on or off, until Settings has it.
            "engine" -> {
                val on = intent.getBooleanExtra("on", true)
                dev.lelonio.square.playback.soloist.SoloistEngine.setEnabled(app, on)
                if (!on) dev.lelonio.square.playback.soloist.SoloistEngine.stop()
                Log.i(TAG, "Soloist engine ${if (on) "on" else "off"}")
            }
            // A Connect command from the Spotify engine's session to a device,
            // to see what Soloist accepts that its WebSocket cannot say.
            "connect" -> {
                val device = intent.getStringExtra("device").orEmpty()
                val body = intent.getStringExtra("body").orEmpty()
                thread(name = "soloist-connect") {
                    val result = runCatching { dev.lelonio.square.nativecore.NativeBridge.remoteCommand(device, body) }
                    Log.i(TAG, "connect -> $device: ${result.exceptionOrNull()?.message ?: "accepted"}")
                }
            }
            // The stream quality, in Soloist's own per-account prefs: 5 is FLAC
            // on 1.3.10 (see spotatui's prefs.rs). Read when Soloist starts, so
            // stop it first and start it again after.
            "quality" -> {
                val level = intent.getIntExtra("level", 5)
                val users = File(app.filesDir, "soloist/data/settings/Users")
                val wanted = linkedMapOf(
                    "audio.play_bitrate_enumeration" to "$level",
                    "audio.play_bitrate_non_metered_enumeration" to "$level",
                    "audio.play_bitrate_non_metered_migrated" to "true",
                    "audio.normalize_v2" to "false",
                )
                users.listFiles().orEmpty().map { File(it, "prefs") }.filter { it.isFile }.forEach { prefs ->
                    val kept = prefs.readLines().filter { line ->
                        line.isNotBlank() && line.substringBefore('=') !in wanted.keys
                    }
                    prefs.writeText((kept + wanted.map { (k, v) -> "$k=$v" }).joinToString("\n", postfix = "\n"))
                    Log.i(TAG, "quality $level written to ${prefs.parentFile?.name}")
                }
            }
            else -> {
                val args = intent.getStringExtra("args").orEmpty()
                val env = intent.getStringExtra("env").orEmpty()
                val exe = intent.getStringExtra("exe") ?: "soloist"
                val bridge = intent.getBooleanExtra("bridge", false)
                thread(name = "soloist-probe") {
                    val withBridge = if (bridge) {
                        val fifo = File(app.cacheDir, "soloist-pcm")
                        dev.lelonio.square.playback.soloist.SoloistAudio.open(fifo)
                        "$env SQUARE_PCM_FIFO=${fifo.path}".trim()
                    } else {
                        env
                    }
                    runCatching { run(app, exe, args, withBridge) }
                        .onFailure { Log.e(TAG, "probe failed", it) }
                }
            }
        }
    }

    private fun install(app: Context): File {
        val src = File(app.getExternalFilesDir(null), "soloist")
        val dst = File(app.filesDir, "soloist")
        src.walkTopDown().filter { it.isFile }.forEach { file ->
            val out = File(dst, file.relativeTo(src).path)
            if (!out.exists() || out.length() != file.length() || out.lastModified() < file.lastModified()) {
                out.parentFile?.mkdirs()
                file.copyTo(out, overwrite = true)
            }
            out.setReadable(true, true)
            out.setExecutable(true, true)
        }
        return dst
    }

    private fun run(app: Context, exe: String, args: String, env: String) {
        val dir = install(app)
        val lib = File(dir, "lib")
        val key = File(dir, "key").takeIf { it.exists() }?.readText()?.trim().orEmpty()
        val argv = args.split(' ').filter { it.isNotEmpty() }
        // `+name`: a program of our own run as it is, which then gets the
        // loader line itself through `@LOADER`.
        val loader = listOf(File(lib, "ld-linux-aarch64.so.1").path, "--library-path", lib.path)
        val resolved = argv.flatMap {
            when (it) {
                "@KEY" -> listOf(key)
                "@LOADER" -> loader
                "@SOLOIST" -> listOf(File(dir, "soloist").path)
                else -> listOf(it)
            }
        }
        val cmd = if (exe.startsWith("+")) {
            listOf(File(dir, exe.removePrefix("+")).path) + resolved
        } else {
            loader + File(dir, exe).path + resolved
        }

        val builder = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true)
        builder.environment().apply {
            put("SSL_CERT_FILE", File(dir, "ca.pem").path)
            put("HOME", dir.path)
            put("TMPDIR", app.cacheDir.path)
            env.split(' ').filter { '=' in it }.forEach {
                val (k, v) = it.split('=', limit = 2)
                put(k, v)
            }
        }
        Log.i(TAG, "exec $exe ${argv.joinToString(" ")} (env: $env)")
        val process = builder.start()
        running = process
        val codec = Regex("""\bcodec: (\w+)""")
        process.inputStream.bufferedReader().forEachLine { line ->
            Log.i(TAG, line)
            codec.find(line)?.let { dev.lelonio.square.playback.StreamQuality.codec(it.groupValues[1]) }
        }
        dev.lelonio.square.playback.StreamQuality.clear()
        Log.i(TAG, "exit ${process.waitFor()}")
    }

    companion object {
        private const val TAG = "SoloistProbe"

        @Volatile
        private var running: Process? = null
    }
}
