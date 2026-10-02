package dev.lelonio.square.backend.youtube

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * A YouTube stream fetched a piece at a time, read as one.
 *
 * YouTube lets a single long request run for a few seconds and then holds it
 * back: a video played for seven seconds and stood still for ten. Here each
 * piece is a request of its own, too short to be held back, and the next is
 * asked for as soon as one runs out — below the player, which sees one stream
 * and its end only where the file really ends.
 */
@UnstableApi
class ChunkedDataSource(private val upstream: DataSource) : DataSource {

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource() = ChunkedDataSource(upstream.createDataSource())
    }

    private var spec: DataSpec? = null
    private var chunked = false
    /** Where the next piece starts, from the start of the file. */
    private var position = 0L
    /** Bytes still wanted in all, or unset for "to the end". */
    private var remaining = C.LENGTH_UNSET.toLong()
    private var pieceLeft = 0L
    private var opened = false

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        chunked = dataSpec.uri.scheme == "https" && dataSpec.uri.host.orEmpty().endsWith("googlevideo.com")
        if (!chunked) {
            opened = true
            return upstream.open(dataSpec)
        }
        position = dataSpec.position
        remaining = dataSpec.length
        val first = openPiece()
        // The whole of what is left, where the server said how big the file is.
        val total = upstream.responseHeaders["Content-Range"]?.firstOrNull()
            ?.substringAfter('/')?.toLongOrNull()
        return when {
            remaining != C.LENGTH_UNSET.toLong() -> remaining
            total != null -> total - dataSpec.position
            else -> if (first < CHUNK_BYTES) first else C.LENGTH_UNSET.toLong()
        }
    }

    private fun openPiece(): Long {
        val wanted = if (remaining == C.LENGTH_UNSET.toLong()) CHUNK_BYTES else minOf(CHUNK_BYTES, remaining)
        val piece = spec!!.buildUpon().setPosition(position).setLength(wanted).build()
        val length = upstream.open(piece)
        opened = true
        pieceLeft = if (length == C.LENGTH_UNSET.toLong()) wanted else length
        return pieceLeft
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!chunked) return upstream.read(buffer, offset, length)
        while (true) {
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            if (!opened) {
                // The next piece. Asked past the end, the server says 416.
                val next = try {
                    openPiece()
                } catch (e: androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                    if (e.responseCode == 416) return C.RESULT_END_OF_INPUT
                    throw e
                }
                if (next == 0L) return C.RESULT_END_OF_INPUT
            }
            val read = upstream.read(buffer, offset, minOf(length.toLong(), pieceLeft).toInt())
            if (read == C.RESULT_END_OF_INPUT) {
                // Shorter than asked for: the file ends inside this piece.
                if (pieceLeft > 0) return C.RESULT_END_OF_INPUT
                upstream.close()
                opened = false
                continue
            }
            position += read
            pieceLeft -= read
            if (remaining != C.LENGTH_UNSET.toLong()) remaining -= read
            if (pieceLeft == 0L) {
                upstream.close()
                opened = false
            }
            return read
        }
    }

    override fun getUri(): Uri? = upstream.uri ?: spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        if (opened) upstream.close()
        opened = false
        spec = null
    }

    private companion object {
        /** Half a megabyte: a few seconds of 1080p, a minute of sound. */
        const val CHUNK_BYTES = 512L * 1024
    }
}
