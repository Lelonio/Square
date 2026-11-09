package dev.lelonio.square.backend.lyrics

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import dev.lelonio.square.data.LocalLibrary
import dev.lelonio.square.data.LyricLine
import dev.lelonio.square.data.Lyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.File
import java.io.InputStream

/**
 * Words for the phone's own music, from the phone (#158).
 *
 * Two places, in this order:
 *
 *  - A `.lrc` file beside the song with the same name: `song.mp3` and
 *    `song.lrc` in one folder, the way every desktop player has done it.
 *  - The words written into the song's own tags: an MP3's `USLT` frame, a FLAC
 *    or Ogg file's `LYRICS` comment. Taggers often put a whole LRC there, so
 *    those are read as timed when they are.
 *
 * Reading the `.lrc` takes more than the audio permission from Android 13 on:
 * a lyrics file is not media, and that permission only reaches media. Up to
 * Android 12 the file is read beside the song directly; from 13 the listener
 * picks their music folder once in the settings, and the file is opened
 * through that grant ([folder]). The tags are inside the song, which the audio
 * permission does reach, so those need nothing.
 */
object LocalLyrics {

    private var context: Context? = null

    /** The folder the listener gave access to, as a tree URI, or blank. */
    var folder: () -> String = { "" }

    fun attach(context: Context) {
        this.context = context.applicationContext
    }

    fun handles(uri: String): Boolean = LocalLibrary.isLocal(uri)

    suspend fun lyrics(uri: String): Lyrics? = withContext(Dispatchers.IO) {
        val context = context ?: return@withContext null
        if (!handles(uri)) return@withContext null
        val id = LocalLibrary.idOfUri(uri).takeIf { it >= 0 } ?: return@withContext null
        val path = path(context, id)
        val beside = path?.let { sidecar(context, it) }
        val text = beside ?: runCatching {
            val audio = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
            context.contentResolver.openInputStream(audio)?.use(::embedded)
        }.getOrNull()
        text?.let(::parse)
    }

    /** Where the song lies on the phone, from the index. */
    private fun path(context: Context, id: Long): String? = runCatching {
        context.contentResolver.query(
            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
            @Suppress("DEPRECATION") arrayOf(MediaStore.Audio.Media.DATA),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    /** The `.lrc` beside the song: read directly where Android allows, else through the folder grant. */
    private fun sidecar(context: Context, path: String): String? {
        val audio = File(path)
        val names = LRC_EXTENSIONS.map { audio.nameWithoutExtension + "." + it }
        audio.parentFile?.let { dir ->
            for (name in names) {
                val file = File(dir, name)
                val read = runCatching { if (file.canRead()) file.readText() else null }.getOrNull()
                if (read != null) return read
            }
        }
        val tree = folder().takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return null
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
        val audioId = documentId(path) ?: return null
        val parent = audioId.substringBeforeLast('/', missingDelimiterValue = audioId.substringBefore(':') + ":")
        for (name in names) {
            val lrcId = if (parent.endsWith(":")) parent + name else "$parent/$name"
            // Only inside what was granted; the provider would refuse anyway.
            if (!within(lrcId, treeId)) return null
            val document = DocumentsContract.buildDocumentUriUsingTree(tree, lrcId)
            val read = runCatching {
                context.contentResolver.openInputStream(document)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
            if (read != null) return read
        }
        return null
    }

    /**
     * The external storage provider's id for a file: `primary:Music/a.mp3` for
     * the phone's own storage, `1234-ABCD:Music/a.mp3` for a card.
     */
    private fun documentId(path: String): String? {
        @Suppress("DEPRECATION")
        val primary = Environment.getExternalStorageDirectory().absolutePath
        return when {
            path.startsWith("$primary/") -> "primary:" + path.removePrefix("$primary/")
            path.startsWith("/storage/") -> {
                val rest = path.removePrefix("/storage/")
                rest.substringBefore('/') + ":" + rest.substringAfter('/', "")
            }
            else -> null
        }
    }

    private fun within(documentId: String, treeId: String): Boolean =
        treeId.endsWith(":") && documentId.startsWith(treeId) ||
            documentId.startsWith("$treeId/")

    // --- The song's own tags ---------------------------------------------

    /** The words in the song's tags, or null; only the start of the file is read. */
    internal fun embedded(stream: InputStream): String? {
        val input = DataInputStream(stream.buffered())
        val head = ByteArray(4)
        input.readFully(head)
        return when {
            head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() ->
                id3(input, version = head[3].toInt())
            String(head, Charsets.ISO_8859_1) == "fLaC" -> flac(input)
            String(head, Charsets.ISO_8859_1) == "OggS" -> ogg(input)
            else -> null
        }
    }

    /** An ID3v2 tag's `USLT` frame (`ULT` in version 2), after the four bytes already read. */
    private fun id3(input: DataInputStream, version: Int): String? {
        input.readUnsignedByte() // revision
        val flags = input.readUnsignedByte()
        var remaining = syncsafe(input.readInt())
        if (flags and 0x40 != 0 && version >= 3) {
            // An extended header, skipped whole.
            val size = input.readInt().let { if (version == 4) syncsafe(it) - 4 else it }
            input.skipFully(size)
            remaining -= size + 4
        }
        val idLength = if (version == 2) 3 else 4
        val headerLength = if (version == 2) 6 else 10
        while (remaining > headerLength) {
            val id = ByteArray(idLength).also(input::readFully).toString(Charsets.ISO_8859_1)
            if (id[0] == '\u0000') return null // padding: no more frames
            val size = when (version) {
                2 -> (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
                3 -> input.readInt()
                else -> syncsafe(input.readInt())
            }
            if (version != 2) input.readUnsignedShort() // frame flags
            if (size <= 0 || size > remaining - headerLength) return null
            remaining -= headerLength + size
            if (id == "USLT" || id == "ULT") {
                val body = ByteArray(size).also(input::readFully)
                return uslt(body)?.takeIf { it.isNotBlank() }
            }
            input.skipFully(size)
        }
        return null
    }

    /** Encoding, language, a description ended by a terminator, then the words. */
    private fun uslt(body: ByteArray): String? {
        if (body.size < 5) return null
        val encoding = body[0].toInt()
        val wide = encoding == 1 || encoding == 2
        var index = 4
        // The description, up to its terminator: one zero byte, or two on a
        // two-byte boundary for UTF-16.
        if (wide) {
            while (index + 1 < body.size && !(body[index] == 0.toByte() && body[index + 1] == 0.toByte())) index += 2
            index += 2
        } else {
            while (index < body.size && body[index] != 0.toByte()) index++
            index += 1
        }
        if (index >= body.size) return null
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return String(body, index, body.size - index, charset).trimEnd('\u0000')
    }

    /** A FLAC file's Vorbis comment block, after the `fLaC` already read. */
    private fun flac(input: DataInputStream): String? {
        while (true) {
            val header = input.readUnsignedByte()
            val last = header and 0x80 != 0
            val type = header and 0x7F
            val length = (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
            if (type == 4) return vorbisComments(ByteArray(length).also(input::readFully))
            input.skipFully(length)
            if (last) return null
        }
    }

    /**
     * An Ogg file's comment header: the second packet, which for Vorbis and
     * Opus starts within the first pages. Read from the first 256 KB, enough
     * for any comment header without a picture in it.
     */
    private fun ogg(input: DataInputStream): String? {
        val start = ByteArray(OGG_WINDOW)
        var read = 0
        while (read < start.size) {
            val got = input.read(start, read, start.size - read)
            if (got < 0) break
            read += got
        }
        val bytes = start.copyOf(read)
        val vorbis = indexOf(bytes, "\u0003vorbis".toByteArray(Charsets.ISO_8859_1))
        val opus = indexOf(bytes, "OpusTags".toByteArray(Charsets.ISO_8859_1))
        val at = when {
            vorbis >= 0 -> vorbis + 7
            opus >= 0 -> opus + 8
            else -> return null
        }
        return vorbisComments(bytes.copyOfRange(at, bytes.size))
    }

    /** `LYRICS=` (or `UNSYNCEDLYRICS=`) from a Vorbis comment list, little-endian. */
    private fun vorbisComments(block: ByteArray): String? = runCatching {
        val buffer = java.nio.ByteBuffer.wrap(block).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val vendor = buffer.int
        buffer.position(buffer.position() + vendor)
        val count = buffer.int
        repeat(count) {
            val length = buffer.int
            if (length < 0 || length > buffer.remaining()) return@runCatching null
            val entry = String(block, buffer.position(), length, Charsets.UTF_8)
            buffer.position(buffer.position() + length)
            val key = entry.substringBefore('=').uppercase()
            if (key in VORBIS_KEYS) return@runCatching entry.substringAfter('=').takeIf { it.isNotBlank() }
        }
        null
    }.getOrNull()

    // --- Reading the words ----------------------------------------------------

    /**
     * LRC when any line carries a time, plain words otherwise.
     *
     * A line may carry several times, for a chorus written out once, and the
     * file may say `[offset:+250]` to move every line; both are honoured.
     */
    internal fun parse(raw: String): Lyrics? {
        val text = raw.removePrefix("﻿")
        val offset = OFFSET.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val timed = text.lines().flatMap { line ->
            val stamps = STAMP.findAll(line).toList()
            if (stamps.isEmpty() || stamps.first().range.first != 0) return@flatMap emptyList()
            // Only the times at the start of the line, back to back.
            val leading = stamps.fold(mutableListOf<MatchResult>()) { kept, match ->
                if (kept.isEmpty() || match.range.first == kept.last().range.last + 1) kept += match
                kept
            }
            val words = line.substring(leading.last().range.last + 1).trim()
            leading.map { match ->
                val (minutes, seconds, fraction) = match.destructured
                val ms = minutes.toLong() * 60_000 + seconds.toLong() * 1_000 +
                    fraction.padEnd(3, '0').take(3).toLong()
                // A positive offset in LRC means the words come sooner.
                LyricLine(startTimeMs = (ms - offset).coerceAtLeast(0), text = words)
            }
        }.sortedBy { it.startTimeMs }
        if (timed.isNotEmpty()) return Lyrics(timed, synced = true)
        val plain = text.lines()
            .filterNot { TAG.matches(it.trim()) }
            .map { it.trim() }
            .dropWhile { it.isEmpty() }
            .dropLastWhile { it.isEmpty() }
        if (plain.isEmpty()) return null
        return Lyrics(plain.map { LyricLine(startTimeMs = null, text = it) }, synced = false)
    }

    private val STAMP = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val OFFSET = Regex("\\[offset:\\s*([+-]?\\d+)\\s*]", RegexOption.IGNORE_CASE)
    private val TAG = Regex("\\[[a-zA-Z]+:.*]")

    private val LRC_EXTENSIONS = listOf("lrc", "LRC")
    private val VORBIS_KEYS = setOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS")
    private const val OGG_WINDOW = 256 * 1024

    private fun syncsafe(value: Int): Int =
        (value and 0x7F) or ((value shr 8) and 0x7F shl 7) or ((value shr 16) and 0x7F shl 14) or ((value shr 24) and 0x7F shl 21)

    private fun DataInputStream.skipFully(count: Int) {
        var left = count.toLong()
        while (left > 0) {
            val skipped = skip(left)
            if (skipped <= 0) {
                if (read() < 0) throw java.io.EOFException()
                left -= 1
            } else {
                left -= skipped
            }
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
