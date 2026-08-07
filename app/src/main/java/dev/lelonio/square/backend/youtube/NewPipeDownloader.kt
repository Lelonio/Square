package dev.lelonio.square.backend.youtube

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/**
 * The HTTP client NewPipeExtractor asks for.
 *
 * The extractor ships no networking of its own on purpose, so this is the one
 * piece that has to exist here. It is the app's own OkHttp rather than a second
 * client: connection pooling across the catalogue and the media requests is
 * most of what makes browsing feel immediate.
 */
class NewPipeDownloader(private val http: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder()
            .method(
                request.httpMethod(),
                request.dataToSend()?.toRequestBody(),
            )
            .url(request.url())
            // YouTube serves a consent interstitial instead of content to
            // clients it thinks are in the EU and have not answered it. The
            // extractor expects the cookie to be set for it.
            .addHeader("User-Agent", USER_AGENT)

        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }

        http.newCall(builder.build()).execute().use { response ->
            // 429 is YouTube asking for a captcha. Distinguished because it is
            // the one failure a retry cannot fix and the UI has to explain.
            if (response.code == HTTP_TOO_MANY_REQUESTS) {
                throw ReCaptchaException("reCaptcha challenge requested", request.url())
            }
            val body = response.body?.string()
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                body,
                response.request.url.toString(),
            )
        }
    }

    companion object {
        private const val HTTP_TOO_MANY_REQUESTS = 429

        /**
         * A desktop browser's.
         *
         * InnerTube answers a mobile or unknown agent with a different, smaller
         * response shape than the extractor is written against.
         */
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        fun create(): NewPipeDownloader = NewPipeDownloader(
            OkHttpClient.Builder()
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build(),
        )

        private const val READ_TIMEOUT_SECONDS = 30L
    }
}
