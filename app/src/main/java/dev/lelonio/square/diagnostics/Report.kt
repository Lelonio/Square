package dev.lelonio.square.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import dev.lelonio.square.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What someone reporting a crash can attach to an issue without a computer.
 *
 * The only way to see why the app stopped used to be `adb logcat`, which asks
 * for a cable, a computer and a terminal of the person who has just had the
 * app close on them. This puts together what the phone already knows — the
 * versions, why Android says the app last ended, and the app's own recent log
 * — takes the account out of it, and hands it to the share sheet.
 *
 * Nothing leaves the phone on its own. The file is written to the cache and
 * goes wherever the person sends it, which is the whole of the privacy model:
 * there is no server this could be sent to even by mistake.
 */
object Report {

    /**
     * Starts keeping crashes, before anything else in the process can have one.
     *
     * Android keeps its own record of why the app ended from Android 11 on,
     * but for a crash in Kotlin it records only that it was one, not where.
     * The system log has the trace, until enough else is written to push it
     * out, which on a busy phone is a matter of minutes. So the trace is also
     * written to a file here, on the way down, and the default handler is left
     * to do what it always did.
     */
    fun install(context: Context) {
        val dir = File(context.filesDir, CRASH_DIR)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                dir.mkdirs()
                val trace = android.util.Log.getStackTraceString(error)
                File(dir, "crash-${System.currentTimeMillis()}.txt").writeText(
                    "${stamp(System.currentTimeMillis())} on ${thread.name}, " +
                        "${BuildConfig.VERSION_NAME}\n$trace",
                )
                // The newest few, which is as far back as anyone asks about.
                dir.listFiles()?.sortedByDescending { it.name }?.drop(CRASHES_KEPT)
                    ?.forEach { it.delete() }
            }
            previous?.uncaughtException(thread, error)
        }
        record(context)
    }

    /**
     * Keeps this run's log in a file of its own, while it runs.
     *
     * The system log is not somewhere to come back to: it is one ring shared by
     * everything on the phone, 256 KB on some of them, and it goes round in a
     * few minutes. By the time somebody whose app has just closed finds the
     * settings, what the app said before it went is usually gone. So a logcat
     * of the app's own lines is left writing to a file for as long as the
     * process lives, and dies with it — which is after the last line it had to
     * say. The last few runs are kept, the one that crashed among them.
     */
    private fun record(context: Context) {
        runCatching {
            val dir = File(context.filesDir, LOG_DIR).apply { mkdirs() }
            val runs = dir.listFiles().orEmpty()
                .groupBy { it.name.substringBefore(".log") }
                .toSortedMap(compareByDescending { it })
            runs.values.drop(RUNS_KEPT - 1).flatten().forEach { it.delete() }
            val file = File(dir, "run-${System.currentTimeMillis()}.log")
            ProcessBuilder(
                "logcat", "-v", "threadtime", "-b", "main,system,crash",
                "--pid=${android.os.Process.myPid()}",
                "-f", file.path, "-r", RUN_KB.toString(), "-n", "1",
            ).redirectErrorStream(true).start()
        }.onFailure { android.util.Log.w("SquareReport", "log not recorded: $it") }
    }

    /**
     * Writes the report and returns the file.
     *
     * [names] are strings to take out as well as the ones found in the log:
     * the account's display name, which the app knows and the log does not
     * always say.
     */
    suspend fun build(context: Context, names: List<String>): File = withContext(Dispatchers.IO) {
        val text = buildString {
            appendLine("Square ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Written ${stamp(System.currentTimeMillis())}")
            appendLine()

            appendLine("== How the app last ended, as Android recorded it")
            append(exits(context))
            appendLine()

            appendLine("== Crashes the app wrote down")
            val crashes = File(context.filesDir, CRASH_DIR).listFiles()
                ?.sortedByDescending { it.name }.orEmpty()
            if (crashes.isEmpty()) appendLine("None.")
            crashes.forEach { appendLine(it.readText()); appendLine() }
            appendLine()

            appendLine("== Log of the last runs, newest first")
            append(runs(context))
            appendLine()

            appendLine("== What is left in the system log")
            append(log())
        }
        val dir = File(context.cacheDir, REPORT_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        File(dir, "square-report-${BuildConfig.VERSION_NAME}.txt").apply {
            writeText(redact(text, names))
        }
    }

    /** Opens the share sheet on [file]. */
    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.reports", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * Why the app's last few processes ended, from Android 11 on.
     *
     * This is the record that survives a native crash or a freeze, neither of
     * which passes through the handler in [install]: the engine is Rust, and a
     * crash there takes the process with no Kotlin in the way. A freeze comes
     * with Android's dump of every thread, which is the part worth reading.
     */
    private fun exits(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "Not kept before Android 11.\n"
        val manager = context.getSystemService(ActivityManager::class.java) ?: return "Unavailable.\n"
        val exits = runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, EXITS_KEPT)
        }.getOrNull().orEmpty()
        if (exits.isEmpty()) return "None recorded.\n"
        return buildString {
            exits.forEach { exit ->
                appendLine(
                    "${stamp(exit.timestamp)}  ${reason(exit.reason)}" +
                        (exit.description?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "") +
                        (if (exit.pss > 0) ", ${exit.pss / 1024} MB" else ""),
                )
                if (exit.reason == ApplicationExitInfo.REASON_ANR) {
                    runCatching {
                        exit.traceInputStream?.bufferedReader()?.useLines { lines ->
                            lines.take(ANR_LINES).forEach { appendLine("    $it") }
                        }
                    }
                }
            }
        }
    }

    private fun reason(code: Int): String = when (code) {
        ApplicationExitInfo.REASON_CRASH -> "crash (Kotlin)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash (native)"
        ApplicationExitInfo.REASON_ANR -> "not responding"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "closed for memory"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "closed by the user"
        ApplicationExitInfo.REASON_USER_STOPPED -> "stopped by the user"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "too much battery or CPU"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "something it needed stopped"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "a permission changed"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_OTHER -> "other"
        // Named from Android 13 on; by number, so older SDKs still build.
        14 -> "frozen"
        15 -> "the app's state changed"
        16 -> "the app was updated"
        else -> "other ($code)"
    }

    /** The files [record] kept, the newest run first, each file whole. */
    private fun runs(context: Context): String {
        val files = File(context.filesDir, LOG_DIR).listFiles().orEmpty()
        if (files.isEmpty()) return "None.\n"
        return buildString {
            files.groupBy { it.name.substringBefore(".log") }
                .toSortedMap(compareByDescending { it })
                .forEach { (run, parts) ->
                    val started = run.substringAfter("run-").toLongOrNull()?.let(::stamp) ?: run
                    appendLine("--- run started $started")
                    // The rotated half first: it is the older of the two.
                    parts.sortedByDescending { it.name }.forEach { append(it.readText()) }
                    appendLine()
                }
        }
    }

    /**
     * The system log, as much of it as is the app's.
     *
     * An app is shown only its own lines, without asking for anything — and
     * those of earlier runs too, while the buffer still holds them, which is
     * what makes this worth reading after a crash rather than only during one.
     * The crash buffer is where Android puts the trace of the crash itself.
     */
    private fun log(): String = runCatching {
        val process = ProcessBuilder(
            "logcat", "-d", "-v", "threadtime", "-b", "main,system,crash", "-t", LOG_LINES.toString(),
        ).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() }
            .also { process.waitFor() }
    }.getOrElse { "Could not be read: ${it.message}\n" }

    /**
     * Takes out what would say whose phone this is.
     *
     * The account name is found where the engine logs it on sign-in and then
     * removed everywhere it appears, which covers the places nobody thought to
     * list: a URL with it in the path, a playlist owner, a line in a library
     * nobody reads. Then the shapes that are identifying whatever they say —
     * addresses, tokens, codes from a sign-in redirect.
     */
    internal fun redact(text: String, names: List<String>): String {
        val found = NAME_SOURCES.flatMap { pattern ->
            pattern.findAll(text).map { it.groupValues[1] }.toList()
        }
        var out = text
        (found + names)
            .map { it.trim() }
            .filter { it.length >= MIN_NAME }
            .distinct()
            .sortedByDescending { it.length }
            .forEach { out = out.replace(it, "<account>", ignoreCase = true) }
        SHAPES.forEach { (pattern, replacement) -> out = pattern.replace(out, replacement) }
        return out
    }

    private fun stamp(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(ms))

    /** Where the engine and the Web API say the account's name. */
    private val NAME_SOURCES = listOf(
        Regex("""Authenticated as '([^']+)'"""),
        Regex("""username[=:]\s*"?([A-Za-z0-9._-]+)"""),
        Regex("""spotify:user:([A-Za-z0-9._-]+)"""),
        Regex("""/v1/users/([A-Za-z0-9._-]+)"""),
    )

    private val SHAPES = listOf(
        Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""") to "<email>",
        Regex("""(?i)bearer\s+[A-Za-z0-9._~+/=-]+""") to "Bearer <token>",
        Regex("""(?i)((?:access|refresh|id)_token|code|client_secret)(["']?\s*[=:]\s*["']?)[A-Za-z0-9._~+/=-]{8,}""") to "$1$2<token>",
        // What Spotify names new accounts: 31 and twenty-six more, which is an
        // account whether or not the line around it says so.
        Regex("""\b31[a-z0-9]{26}\b""") to "<account>",
    )

    private const val CRASH_DIR = "crashes"
    private const val REPORT_DIR = "reports"
    private const val CRASHES_KEPT = 3
    private const val EXITS_KEPT = 5
    private const val ANR_LINES = 400
    private const val LOG_LINES = 6000
    private const val LOG_DIR = "logs"

    /** Runs kept, the current one included, and how large each may grow, in KB, before it rotates once. */
    private const val RUNS_KEPT = 3
    private const val RUN_KB = 512

    /** Shorter names are words, and taking out every "ed" helps nobody. */
    private const val MIN_NAME = 3
}
