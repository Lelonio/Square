package dev.lelonio.square.playback.soloist

import android.content.Context
import android.net.ConnectivityManager
import java.io.File

/**
 * Where Soloist and its runtime live, and how it is started.
 *
 * The runtime is Debian's glibc (loader, libc and the few libraries Soloist
 * links), adjusted for Android's app sandbox, with two libraries of ours:
 * libsqaudit (a SIGSYS net for any system call the sandbox still refuses) and
 * libsqguard (keeps that net in place), and libpulse.so.0, which hands the
 * audio to Square. For now it is staged in the app's external files dir
 * (`soloist/`) and copied into the app's own data dir, where it may run.
 */
object SoloistRuntime {

    private fun staged(context: Context) = File(context.getExternalFilesDir(null), "soloist")
    private fun home(context: Context) = File(context.filesDir, "soloist")

    fun isInstalled(context: Context): Boolean =
        File(home(context), "soloist").exists() || File(staged(context), "soloist").exists()

    /** Copies what is staged and newer into the data dir; returns that dir. */
    fun install(context: Context): File {
        val src = staged(context)
        val dst = home(context)
        if (src.isDirectory) {
            src.walkTopDown().filter { it.isFile }.forEach { file ->
                val out = File(dst, file.relativeTo(src).path)
                if (!out.exists() || out.length() != file.length() || out.lastModified() < file.lastModified()) {
                    out.parentFile?.mkdirs()
                    file.copyTo(out, overwrite = true)
                }
                out.setReadable(true, true)
                out.setExecutable(true, true)
            }
        }
        File(dst, "data").mkdirs()
        File(dst, "cache").mkdirs()
        return dst
    }

    fun key(context: Context): String =
        File(home(context), "key").takeIf { it.exists() }?.readText()?.trim().orEmpty()

    /** The loader line every run starts with. */
    fun command(dir: File): List<String> {
        val lib = File(dir, "lib")
        return listOf(
            File(lib, "ld-linux-aarch64.so.1").path,
            "--library-path", lib.path,
            File(dir, "soloist").path,
        )
    }

    fun environment(context: Context, dir: File, env: MutableMap<String, String>) {
        val lib = File(dir, "lib")
        env["GLIBC_TUNABLES"] = "glibc.pthread.rseq=0"
        env["LD_AUDIT"] = File(lib, "libsqaudit.so").path
        env["LD_PRELOAD"] = File(lib, "libsqguard.so").path
        env["SSL_CERT_FILE"] = File(dir, "ca.pem").path
        env["HOME"] = dir.path
        env["TMPDIR"] = context.cacheDir.path
    }

    /**
     * The name servers, where our libc looks for them: `r` in the working
     * dir, its /etc/resolv.conf being a path Android does not have.
     */
    fun writeResolver(context: Context, dir: File) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val servers = runCatching {
            connectivity?.getLinkProperties(connectivity.activeNetwork)?.dnsServers
                ?.mapNotNull { it.hostAddress?.substringBefore('%') }
        }.getOrNull().orEmpty().ifEmpty { listOf("1.1.1.1", "1.0.0.1") }
        File(dir, "r").writeText(servers.joinToString("") { "nameserver $it\n" })
    }

    /**
     * The stream quality in Soloist's per-account prefs, which it reads on
     * start: 5 is FLAC on 1.3.10, as spotatui found. Loudness normalisation
     * is turned off, as Square's own engine has it.
     */
    fun writeQuality(dir: File, level: Int) {
        val users = File(dir, "data/settings/Users")
        val wanted = linkedMapOf(
            "audio.play_bitrate_enumeration" to "$level",
            "audio.play_bitrate_non_metered_enumeration" to "$level",
            "audio.play_bitrate_non_metered_migrated" to "true",
            "audio.normalize_v2" to "false",
        )
        users.listFiles().orEmpty().map { File(it, "prefs") }.filter { it.isFile }.forEach { prefs ->
            val kept = prefs.readLines().filter { it.isNotBlank() && it.substringBefore('=') !in wanted.keys }
            prefs.writeText((kept + wanted.map { (k, v) -> "$k=$v" }).joinToString("\n", postfix = "\n"))
        }
    }
}
