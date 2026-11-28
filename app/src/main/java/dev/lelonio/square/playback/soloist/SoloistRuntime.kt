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
 * audio to Square. It ships in the APK as native libraries (`libsq*.so`),
 * which the installer puts in the native library dir: the only place an app
 * targeting API 29+ may run code from. `lib/` in the data dir holds links
 * under the names glibc looks for. Soloist itself is Spotify's, downloaded
 * from Spotify (see SoloistDownload), and run from a memfd by libsqlaunch,
 * since the data dir is not executable. The CA bundle is Android's own.
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

    /** Links the runtime when the APK changed, then copies what is staged and newer; returns the dir. */
    fun install(context: Context): File {
        val dst = home(context)
        linkLibraries(context, dst)
        writeCertificates(dst)
        val src = staged(context)
        if (src.isDirectory) {
            // Not lib/: the runtime runs from the APK's native library dir.
            src.walkTopDown().filter { it.isFile && !it.relativeTo(src).path.startsWith("lib") }.forEach { file ->
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

    private fun native(context: Context) = File(context.applicationInfo.nativeLibraryDir)

    /** Soloist is built for arm64 only: elsewhere the APK carries no runtime and it is not offered. */
    fun isSupported(context: Context): Boolean = File(native(context), "libsqlaunch.so").exists()

    /** glibc's names for the libraries the APK installs under its own. */
    private val LINKS = linkedMapOf(
        "ld-linux-aarch64.so.1" to "libsqld.so",
        "libc.so.6" to "libsqc.so",
        "libm.so.6" to "libsqm.so",
        "libpthread.so.0" to "libsqpthread.so",
        "libdl.so.2" to "libsqdl.so",
        "librt.so.1" to "libsqrt.so",
        "libgcc_s.so.1" to "libsqgcc.so",
        "libatomic.so.1" to "libsqatomic.so",
        "libpulse.so.0" to "libsqpulse.so",
    )

    /**
     * `lib/` as glibc's loader looks for it: links to the native library dir,
     * which moves with every install of the APK, so they are made again when
     * it has. Mapping a library through a link maps the file it points to.
     */
    private fun linkLibraries(context: Context, dst: File) {
        val native = native(context)
        val marker = File(dst, "runtime.version")
        val lib = File(dst, "lib")
        if (marker.exists() && marker.readText() == native.path && File(lib, "libc.so.6").exists()) return
        lib.deleteRecursively()
        lib.mkdirs()
        LINKS.forEach { (name, installed) ->
            runCatching { android.system.Os.symlink(File(native, installed).path, File(lib, name).path) }
                .onFailure { android.util.Log.w("SoloistRuntime", "no link for $name: $it") }
        }
        marker.writeText(native.path)
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
        SoloistEngine.retry(context)
        SoloistEngine.refresh(context)
    }

    /**
     * The line every run starts with: the launcher, which puts Soloist in a
     * memfd, then glibc's loader on it.
     */
    fun command(context: Context, dir: File): List<String> {
        val native = native(context)
        return listOf(
            File(native, "libsqlaunch.so").path,
            File(dir, "soloist").path,
            File(native, "libsqld.so").path,
            "--library-path", File(dir, "lib").path,
            "@BIN",
        )
    }

    fun environment(context: Context, dir: File, env: MutableMap<String, String>) {
        val native = native(context)
        env["GLIBC_TUNABLES"] = "glibc.pthread.rseq=0"
        // Renamed for the launcher, which runs under Android's linker and
        // hands them to glibc's under their own names; see launch.c.
        env["SQ_LD_AUDIT"] = File(native, "libsqaudit.so").path
        env["SQ_LD_PRELOAD"] = File(native, "libsqguard.so").path
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
     * on, as Square's own engine has it (see engine.rs): off, one song came
     * loud and the next quiet.
     */
    fun writeQuality(dir: File, level: Int, normalise: Boolean = true) {
        val users = File(dir, "data/settings/Users")
        val wanted = linkedMapOf(
            "audio.play_bitrate_enumeration" to "$level",
            "audio.play_bitrate_non_metered_enumeration" to "$level",
            "audio.play_bitrate_non_metered_migrated" to "true",
            "audio.normalize_v2" to "$normalise",
        )
        users.listFiles().orEmpty().map { File(it, "prefs") }.filter { it.isFile }.forEach { prefs ->
            val kept = prefs.readLines().filter { it.isNotBlank() && it.substringBefore('=') !in wanted.keys }
            prefs.writeText((kept + wanted.map { (k, v) -> "$k=$v" }).joinToString("\n", postfix = "\n"))
        }
    }
}
