package app.jaytune.providers.innertube.requests

import app.jaytune.providers.innertube.Innertube
import app.jaytune.providers.innertube.models.Context
import app.jaytune.providers.innertube.models.PlayerResponse
import app.jaytune.providers.innertube.models.isEmbeddedPlayer
import app.jaytune.providers.innertube.models.requiresServiceIntegrity
import app.jaytune.providers.innertube.models.usesSignatureTimestamp
import app.jaytune.providers.innertube.models.bodies.PlayerBody
import app.jaytune.providers.utils.runCatchingCancellable
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.util.generateNonce
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

private fun Context.clientKey() = "${client.clientName}@${client.clientVersion}"

/**
 * Requests one client at a time.  This is intentionally ordered like ArchiveTune
 * v14.1.0: WEB_REMIX remains the metadata/first-stream client while native
 * profiles are tried before the last web/TV alternatives.
 */
private suspend fun Innertube.tryContexts(
    body: PlayerBody,
    checkIsValid: Boolean,
    visitorData: String?,
    poToken: String?,
    excludedClientKeys: Set<String>,
    vararg contexts: Context
): PlayerResponse? {
    contexts.forEach { context ->
        if (!currentCoroutineContext().isActive) return null
        if (context.clientKey() in excludedClientKeys) {
            logger.info("Skipping failed stream client ${context.clientKey()}")
            return@forEach
        }

        logger.info("Trying ${context.client.clientName} ${context.client.clientVersion} ${context.client.platform}")
        val cpn = generateNonce(16).decodeToString()
        // ArchiveTune carries one visitor value in PlaybackAuthState and uses it
        // for every fallback.  JayTune must not recreate independent contexts
        // with the static default visitor ID for native/VISIONOS requests.
        val contextWithPlaybackVisitor = context.copy(
            client = context.client.copy(
                defaultVisitorData = visitorData ?: context.client.visitorData
            )
        )
        val requestContext = if (contextWithPlaybackVisitor.client.isEmbeddedPlayer()) {
            contextWithPlaybackVisitor.copy(
                thirdParty = Context.ThirdParty(
                    embedUrl = "https://www.youtube.com/watch?v=${body.videoId}"
                )
            )
        } else {
            contextWithPlaybackVisitor
        }

        runCatchingCancellable {
            client.post(if (context.client.music) PLAYER_MUSIC else PLAYER) {
                setBody(
                    body.copy(
                        context = requestContext,
                        cpn = cpn,
                        // ArchiveTune does not reuse ViTune's legacy anti-throttle
                        // payload.  That value is invalid for several modern client
                        // profiles and made the fallback sequence inconsistent.
                        params = body.params,
                        playbackContext = if (context.client.usesSignatureTimestamp()) {
                            PlayerBody.PlaybackContext(
                                contentPlaybackContext = PlayerBody.PlaybackContext.ContentPlaybackContext(
                                    signatureTimestamp = getSignatureTimestamp(context)?.toIntOrNull()
                                )
                            )
                        } else {
                            null
                        },
                        serviceIntegrityDimensions = poToken
                            ?.takeIf { context.client.requiresServiceIntegrity() }
                            ?.let(PlayerBody::ServiceIntegrityDimensions)
                    )
                )
                requestContext.apply()
            }.body<PlayerResponse>().also { logger.info("Got $it") }
        }
            ?.getOrNull()
            ?.takeIf { !checkIsValid || it.isValid }
            ?.let {
                return it.copy(
                    cpn = cpn,
                    context = requestContext
                )
            }
    }

    return null
}

private val PlayerResponse.isValid
    get() = playabilityStatus?.status == "OK" &&
        streamingData?.adaptiveFormats?.any { it.url != null || it.signatureCipher != null } == true

/**
 * Resolves an InnerTube player response with ArchiveTune's stream fallback order.
 * `poToken` is the video-bound BotGuard token; the same value is subsequently
 * supplied as GVS `pot` only to clients whose policy requires service integrity.
 */
suspend fun Innertube.player(
    body: PlayerBody,
    checkIsValid: Boolean = true,
    visitorData: String? = null,
    poToken: String? = null,
    excludedClientKeys: Set<String> = emptySet()
): Result<PlayerResponse?>? = runCatchingCancellable {
    tryContexts(
        body = body,
        checkIsValid = checkIsValid,
        visitorData = visitorData,
        poToken = poToken,
        excludedClientKeys = excludedClientKeys,
        Context.DefaultWeb,
        Context.DefaultAndroidVr,
        Context.DefaultIOS,
        Context.DefaultAndroid,
        Context.DefaultAndroidMusic,
        Context.DefaultIOSMusic,
        Context.DefaultAndroidCreator,
        Context.DefaultAndroidTestSuite,
        Context.DefaultAndroidUnplugged,
        Context.DefaultIPadOS,
        Context.DefaultVisionOS,
        Context.DefaultTv,
        Context.DefaultTvEmbedded,
        Context.DefaultWebPrimary,
        Context.DefaultWebCreator
    )
}
