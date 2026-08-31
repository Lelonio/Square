package dev.lelonio.square.offline

object DownloadPolicy {
    const val MAX_AUTOMATIC_RETRIES = 4

    fun isRetryableHttp(code: Int): Boolean = code == 408 || code == 425 || code == 429 || code in 500..599

    fun shouldRetry(attempt: Int): Boolean = attempt < MAX_AUTOMATIC_RETRIES

    fun extensionForContentType(contentType: String?): String? = when (contentType?.lowercase()) {
        "audio/webm" -> "webm"
        "audio/mp4", "audio/aac" -> "m4a"
        "audio/mpeg" -> "mp3"
        "audio/ogg", "audio/opus" -> "ogg"
        "audio/wav", "audio/x-wav" -> "wav"
        "audio/flac" -> "flac"
        else -> null
    }
}
