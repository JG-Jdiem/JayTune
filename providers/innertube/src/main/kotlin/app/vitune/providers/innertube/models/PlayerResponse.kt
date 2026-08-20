package app.jaytune.providers.innertube.models

import app.jaytune.providers.innertube.Innertube
import app.jaytune.providers.innertube.NewPipeUtils
import kotlinx.serialization.Serializable
import app.jaytune.android.morideobfuscator.MoriCipherRuntime
import kotlinx.serialization.Transient

@Serializable
data class PlayerResponse(
    val playabilityStatus: PlayabilityStatus?,
    val playerConfig: PlayerConfig?,
    val streamingData: StreamingData?,
    val videoDetails: VideoDetails?,
    @Transient
    val context: Context? = null,
    @Transient
    val cpn: String? = null
) {
    val reason
        get() = if (playabilityStatus != null && playabilityStatus.status != "OK") buildString {
            appendLine("YouTube responded with status '${playabilityStatus.reason.orEmpty()}'")
            playabilityStatus.reason?.let { appendLine("Reason: $it") }
            playabilityStatus.errorScreen?.playerErrorMessageRenderer?.subreason?.text?.let {
                appendLine()
                appendLine(it)
            }
        } else null

    @Serializable
    data class PlayabilityStatus(
        val status: String? = null,
        val reason: String? = null,
        val errorScreen: ErrorScreen? = null
    )

    @Serializable
    data class PlayerConfig(
        val audioConfig: AudioConfig?
    ) {
        @Serializable
        data class AudioConfig(
            internal val loudnessDb: Double?,
            internal val perceptualLoudnessDb: Double?
        ) {
            // For music clients only
            val normalizedLoudnessDb: Float?
                get() = (loudnessDb ?: perceptualLoudnessDb?.plus(7))?.plus(7)?.toFloat()
        }
    }

    @Serializable
    data class StreamingData(
        val adaptiveFormats: List<AdaptiveFormat>?,
        val expiresInSeconds: Long?
    ) {
        val highestQualityFormat: AdaptiveFormat?
            get() = adaptiveFormats?.filter { it.url != null || it.signatureCipher != null }
                ?.let { formats ->
                    formats.findLast { it.itag == 251 || it.itag == 140 }
                        ?: formats.maxBy { it.bitrate ?: 0L }
                }

        @Serializable
        data class AdaptiveFormat(
            val itag: Int,
            val mimeType: String,
            val bitrate: Long?,
            val averageBitrate: Long?,
            val contentLength: Long?,
            val audioQuality: String?,
            val approxDurationMs: Long?,
            val lastModified: Long?,
            val loudnessDb: Double?,
            val audioSampleRate: Int?,
            val url: String?,
            val signatureCipher: String?
        ) {
            /**
             * Mirrors ArchiveTune's branch-independent URL finalization:
             * direct URLs, Mori-deciphered URLs and NewPipe-deciphered URLs all
             * receive the same optional GVS token after their `n`/signature work.
             */
            suspend fun findUrl(
                context: Context,
                videoId: String,
                gvsPoToken: String? = null,
            ): String? {
                val resolvedUrl = url?.let { directUrl ->
                    MoriCipherRuntime.transformNParameter(videoId, directUrl)
                        .recoverCatching { NewPipeUtils.transformNParameter(videoId, directUrl).getOrThrow() }
                        .getOrDefault(directUrl)
                } ?: signatureCipher?.let { cipher ->
                    Innertube.decodeSignatureCipher(
                        context = context,
                        videoId = videoId,
                        cipher = cipher,
                    )
                }

                return resolvedUrl?.appendGvsPoToken(
                    context = context,
                    gvsPoToken = gvsPoToken,
                )
            }

            private fun String.appendGvsPoToken(
                context: Context,
                gvsPoToken: String?,
            ): String {
                if (!context.client.requiresServiceIntegrity()) return this
                val token = gvsPoToken?.takeIf(String::isNotBlank) ?: return this
                if (contains("pot=")) return this

                return this + if (contains("?")) "&pot=$token" else "?pot=$token"
            }
        }
    }

    @Serializable
    data class VideoDetails(
        val videoId: String?
    )
}

@Serializable
data class ErrorScreen(
    val playerErrorMessageRenderer: PlayerErrorMessageRenderer? = null
) {
    @Serializable
    data class PlayerErrorMessageRenderer(
        val subreason: Runs? = null
    )
}
