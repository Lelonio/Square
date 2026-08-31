package dev.lelonio.square.offline

import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class Downloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(30, java.util.concurrent.TimeUnit.MINUTES)
        .build(),
) {
    suspend fun download(
        source: ResolvedDownloadSource,
        destination: File,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        require(source.url.startsWith("https://")) { "download source must use HTTPS" }
        val request = Request.Builder().url(source.url).get().build()
        val call = client.newCall(request)
        currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val code = response.code
                    if (DownloadPolicy.isRetryableHttp(code)) throw TransientDownloadException("HTTP $code")
                    throw PermanentDownloadException(DownloadErrorCode.HTTP, "HTTP $code")
                }
                val body = response.body ?: throw PermanentDownloadException(
                    DownloadErrorCode.MALFORMED_RESPONSE,
                    "response contained no body",
                )
                val contentType = body.contentType()?.toString()?.substringBefore(';')?.lowercase()
                if (contentType != null && !contentType.startsWith("audio/")) {
                    throw PermanentDownloadException(DownloadErrorCode.INVALID_MEDIA, "unsupported media content type")
                }
                val total = body.contentLength()
                destination.parentFile?.mkdirs()
                var downloaded = 0L
                body.byteStream().use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                        output.fd.sync()
                    }
                }
                if (downloaded <= 0L) throw PermanentDownloadException(DownloadErrorCode.INVALID_MEDIA, "empty media response")
                if (total > 0L && downloaded != total) throw TransientDownloadException("response ended before content length")
                DownloadResult(
                    bytes = downloaded,
                    totalBytes = total,
                    contentType = contentType ?: source.contentTypeHint,
                    extension = source.extensionHint
                        ?.takeIf { it.matches(Regex("[a-zA-Z0-9]{1,8}")) }
                        ?: DownloadPolicy.extensionForContentType(contentType)
                        ?: throw PermanentDownloadException(DownloadErrorCode.INVALID_MEDIA, "unknown audio format"),
                )
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            destination.delete()
            throw cancelled
        } catch (error: PermanentDownloadException) {
            destination.delete()
            throw error
        } catch (error: SocketTimeoutException) {
            destination.delete()
            throw TransientDownloadException("network timeout", error)
        } catch (error: IOException) {
            destination.delete()
            throw TransientDownloadException("network failure", error)
        }
    }
}

data class DownloadResult(
    val bytes: Long,
    val totalBytes: Long,
    val contentType: String?,
    val extension: String,
)
