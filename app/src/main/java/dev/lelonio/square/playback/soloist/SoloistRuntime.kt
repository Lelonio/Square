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
 * audio to Square. It ships in the APK's assets and is copied into the app's
 * own data dir, where it may run; Soloist itself is Spotify's and is
 * downloaded from Spotify (see SoloistDownload). The CA bundle is Android's own.
 *
 * Anything staged in the app's external files dir (`soloist/`) is copied over
 * it, for trying a build by hand.
 */
object SoloistRuntime {

    private fun staged(context: Context) = File(context.getExternalFilesDir(null), "soloist")
    fun home(context: Context) = File(context.filesDir, "soloist")

    /** Soloist's own binary, where it runs from. */
    fun binary(context: Context) = File(home(context), "soloist")

    fun isInstalled(context: Context): Boolean =
        binary(context).exists() || File(staged(context), "soloist").exists()

    /** Copies the runtime out of the APK when it changed, then what is staged and newer; returns the dir. */
    fun install(context: Context): File {
        val dst = home(context)
        installAssets(context, dst)
        writeCertificates(dst)
        val src = staged(context)
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

    /** Once per APK: the runtime as this build ships it. */
    private fun installAssets(context: Context, dst: File) {
        val built = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        }.getOrDefault("0")
        val marker = File(dst, "runtime.version")
        if (marker.exists() && marker.readText() == built && File(dst, "lib/libc.so.6").exists()) return
        val lib = File(dst, "lib").apply { mkdirs() }
        context.assets.list("soloist/lib").orEmpty().forEach { name ->
            val out = File(lib, name)
            val tmp = File(lib, "$name.tmp")
            context.assets.open("soloist/lib/$name").use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.setReadable(true, true)
            tmp.setExecutable(true, true)
            tmp.renameTo(out)
        }
        marker.writeText(built)
    }

    /**
     * The phone's trusted roots, as the one bundle Soloist reads: Android's
     * store is a directory of PEM files, kept up to date by the system.
     */
    private fun writeCertificates(dst: File) {
        val out = File(dst, "ca.pem")
        val dirs = listOf(File("/apex/com.android.conscrypt/cacerts"), File("/system/etc/security/cacerts"))
        val source = dirs.firstOrNull { it.isDirectory && !it.list().isNullOrEmpty() } ?: return
        val newest = source.listFiles().orEmpty().maxOfOrNull { it.lastModified() } ?: 0
        if (out.exists() && out.length() > 0 && out.lastModified() >= newest) return
        val pem = StringBuilder()
        source.listFiles().orEmpty().sortedBy { it.name }.forEach { file ->
            val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
            val begin = text.indexOf("-----BEGIN CERTIFICATE-----")
            val end = text.indexOf("-----END CERTIFICATE-----")
            if (begin >= 0 && end > begin) pem.append(text, begin, end + 25).append('\n')
        }
        if (pem.isNotEmpty()) out.writeText(pem.toString())
    }

    fun key(context: Context): String =
        File(home(context), "key").takeIf { it.exists() }?.readText()?.trim().orEmpty()

    /** The listener's Soloist key, from Spotify's developer dashboard; kept only in the app's data. */
    fun setKey(context: Context, key: String) {
        val file = File(home(context).apply { mkdirs() }, "key")
        if (key.isBlank()) file.delete() else file.writeText(key.trim())
        SoloistEngine.refresh(context)
    }

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
