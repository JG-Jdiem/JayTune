/*
 * Adapted from ArchiveTune's local NewPipe bridge.
 * ArchiveTune (2026) © Rukamori — GPL-3.0.
 * See the original notices in the referenced upstream project.
 */
package app.jaytune.providers.innertube

import io.ktor.http.URLBuilder
import io.ktor.http.parseQueryString
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Stable local fallback for YouTube player JavaScript transformations.
 *
 * MoriCipherRuntime remains the first choice. This object is called only when
 * the current player script cannot be compiled by Mori, matching ArchiveTune's
 * fallback sequence for signatureCipher and the n throttling parameter.
 */
internal object NewPipeUtils {
    init {
        NewPipe.init(NewPipeDownloader())
    }

    fun resolveSignatureCipher(
        videoId: String,
        cipher: String,
    ): Result<String> =
        runCatching {
            val params = parseQueryString(cipher)
            val encryptedSignature = params["s"] ?: error("Missing signatureCipher parameter s")
            val signatureParameter = params["sp"].orEmpty().ifBlank { "signature" }
            val streamUrl = params["url"] ?: error("Missing signatureCipher URL")

            val resolvedSignature = withPlayerCacheRecovery {
                YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, encryptedSignature)
            }
            val signedUrl = URLBuilder(streamUrl).apply {
                parameters[signatureParameter] = resolvedSignature
            }.buildString()

            transformNParameter(videoId, signedUrl).getOrThrow()
        }

    fun transformNParameter(
        videoId: String,
        url: String,
    ): Result<String> =
        runCatching {
            if (!url.contains("?n=") && !url.contains("&n=")) return@runCatching url
            withPlayerCacheRecovery {
                YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, url)
            }
        }

    private inline fun <T> withPlayerCacheRecovery(block: () -> T): T =
        try {
            block()
        } catch (error: Exception) {
            if (!error.isStalePlayerJavaScriptFailure()) throw error
            runCatching { YoutubeJavaScriptPlayerManager.clearAllCaches() }
            block()
        }

    private fun Throwable.isStalePlayerJavaScriptFailure(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (
                current.message?.contains("deobfuscation function", ignoreCase = true) == true ||
                current.message?.contains("player javascript", ignoreCase = true) == true
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private class NewPipeDownloader : Downloader() {
        private val client =
            OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .build()

        @Throws(IOException::class, ReCaptchaException::class)
        override fun execute(request: Request): Response {
            val requestBuilder =
                okhttp3.Request.Builder()
                    .url(request.url())
                    .method(request.httpMethod(), request.dataToSend()?.toRequestBody())

            var hasUserAgent = false
            request.headers().forEach { (name, values) ->
                if (name.equals("User-Agent", ignoreCase = true) && values.isNotEmpty()) {
                    hasUserAgent = true
                }
                requestBuilder.removeHeader(name)
                values.forEach { value -> requestBuilder.addHeader(name, value) }
            }
            if (!hasUserAgent) {
                requestBuilder.header("User-Agent", WEB_USER_AGENT)
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (response.code == 429) {
                    throw ReCaptchaException("reCaptcha Challenge requested", request.url())
                }
                return Response(
                    response.code,
                    response.message,
                    response.headers.toMultimap(),
                    response.body.string(),
                    response.request.url.toString(),
                )
            }
        }
    }

    private const val WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
}
