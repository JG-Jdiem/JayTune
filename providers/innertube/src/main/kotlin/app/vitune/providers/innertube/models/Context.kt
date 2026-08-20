package app.jaytune.providers.innertube.models

import app.jaytune.providers.innertube.Innertube
import app.jaytune.providers.innertube.json
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMessageBuilder
import io.ktor.http.parameters
import io.ktor.http.userAgent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.Locale

@Serializable
data class Context(
    val client: Client,
    val thirdParty: ThirdParty? = null,
    val request: Request = Request(),
    val user: User? = User()
) {
    @Serializable
    data class Client(
        @Transient
        val clientId: Int = 0,
        val clientName: String,
        val clientVersion: String,
        val platform: String? = null,
        val hl: String = "en",
        val gl: String = "US",
        @SerialName("visitorData")
        val defaultVisitorData: String = DEFAULT_VISITOR_DATA,
        val androidSdkVersion: Int? = null,
        val userAgent: String? = null,
        val referer: String? = null,
        val deviceMake: String? = null,
        val deviceModel: String? = null,
        val osName: String? = null,
        val osVersion: String? = null,
        val acceptHeader: String? = null,
        val timeZone: String? = "UTC",
        val utcOffsetMinutes: Int? = 0,
        @Transient
        val apiKey: String? = null,
        @Transient
        val music: Boolean = false
    ) {
        @Serializable
        data class Configuration(
            @SerialName("PLAYER_JS_URL")
            val playerUrl: String? = null,
            @SerialName("WEB_PLAYER_CONTEXT_CONFIGS")
            val contextConfigs: Map<String, ContextConfig>? = null,
            @SerialName("VISITOR_DATA")
            val visitorData: String? = null,
            @SerialName("INNERTUBE_CONTEXT")
            val innertubeContext: Context
        ) {
            @Serializable
            data class ContextConfig(
                val jsUrl: String? = null
            )
        }

        @Transient
        private val mutex = Mutex()

        @Transient
        private var ytcfg: Configuration? = null

        private val baseUrl
            get() = when {
                platform == "TV" -> "https://www.youtube.com/tv"
                music -> "https://music.youtube.com/"
                else -> "https://www.youtube.com/"
            }
        val root get() = if (music) "https://music.youtube.com/" else "https://www.youtube.com/"

        internal val jsUrl
            get() = ytcfg?.playerUrl
                ?: ytcfg?.contextConfigs?.firstNotNullOfOrNull { it.value.jsUrl }

        val visitorData
            get() = ytcfg?.visitorData
                ?: ytcfg?.innertubeContext?.client?.defaultVisitorData
                ?: defaultVisitorData

        companion object {
            private val YTCFG_REGEX = "ytcfg\\.set\\s*\\(\\s*(\\{[\\s\\S]+?\\})\\s*\\)".toRegex()
        }

        context(HttpMessageBuilder)
        fun apply() {
            userAgent?.let { userAgent(it) }

            headers {
                set("Content-Type", "application/json")
                set("X-Goog-Api-Format-Version", "1")
                referer?.let {
                    set("Referer", it)
                    set("X-Origin", it.trimEnd('/'))
                }
                set("X-Youtube-Bootstrap-Logged-In", "false")
                set("X-YouTube-Client-Name", clientId.toString())
                set("X-YouTube-Client-Version", clientVersion)
                apiKey?.let { set("X-Goog-Api-Key", it) }
                set("X-Goog-Visitor-Id", visitorData)
            }

            parameters {
                apiKey?.let { set("key", it) }
                set("prettyPrint", "false")
            }
        }

        suspend fun getConfiguration(): Configuration? = mutex.withLock {
            ytcfg ?: runCatching {
                val playerPage = Innertube.client.get(baseUrl) {
                    userAgent?.let { header("User-Agent", it) }
                }.bodyAsText()

                val objStr = YTCFG_REGEX
                    .find(playerPage)
                    ?.groups
                    ?.get(1)
                    ?.value
                    ?.trim()
                    ?.takeIf { it.isNotBlank() } ?: return@runCatching null

                json.decodeFromString<Configuration>(objStr).also { ytcfg = it }
            }.getOrElse {
                it.printStackTrace()
                null
            }
        }
    }

    @Serializable
    data class ThirdParty(
        val embedUrl: String
    )

    @Serializable
    data class Request(
        val internalExperimentFlags: List<String> = emptyList(),
        val useSsl: Boolean = true
    )

    @Serializable
    data class User(
        val lockedSafetyMode: Boolean = false
    )

    context(HttpMessageBuilder)
    fun apply() = client.apply()

    companion object {
        private val Context.withLang: Context
            get() {
                val locale = Locale.getDefault()

                return copy(
                    client = client.copy(
                        hl = locale
                            .toLanguageTag()
                            .replace("-Hant", "")
                            .takeIf { it in validLanguageCodes } ?: "en",
                        gl = locale
                            .country
                            .takeIf { it in validCountryCodes } ?: "US"
                    )
                )
            }

        const val DEFAULT_VISITOR_DATA = "CgtsZG1ySnZiQWtSbyiMjuGSBg%3D%3D"
        private const val MUSIC_REFERER = "https://music.youtube.com/"
        private const val TV_REFERER = "https://www.youtube.com/tv"

        /** The metadata client and first stream candidate, exactly as ArchiveTune v14.1.0. */
        val DefaultWeb get() = DefaultWebNoLang.withLang

        val DefaultWebNoLang = Context(
            client = Client(
                clientId = 67,
                clientName = "WEB_REMIX",
                clientVersion = "1.20260213.01.00",
                userAgent = UserAgents.WEB_REMIX,
                referer = MUSIC_REFERER,
                music = true
            )
        )

        /** Compatibility alias for existing browse and BotGuard callers. */
        val DefaultWebToken get() = DefaultWeb

        val DefaultAndroidVr = Context(
            client = Client(
                clientId = 28,
                clientName = "ANDROID_VR",
                clientVersion = "1.65.10",
                osName = "Android",
                osVersion = "12L",
                deviceMake = "Oculus",
                deviceModel = "Quest 3",
                androidSdkVersion = 32,
                userAgent = UserAgents.ANDROID_VR_165,
                referer = MUSIC_REFERER,
                music = true
            )
        )

        val DefaultIOS = Context(
            client = Client(
                clientId = 5,
                clientName = "IOS",
                clientVersion = "19.29.1",
                osName = "iOS",
                osVersion = "17.5.1.21F90",
                deviceMake = "Apple",
                deviceModel = "iPhone16,2",
                userAgent = UserAgents.IOS,
                referer = MUSIC_REFERER,
                music = true
            )
        )

        val DefaultAndroid = Context(
            client = Client(
                clientId = 3,
                clientName = "ANDROID",
                clientVersion = "21.10.38",
                osName = "Android",
                osVersion = "15",
                deviceMake = "Google",
                deviceModel = "Pixel 9 Pro",
                androidSdkVersion = 35,
                userAgent = UserAgents.ANDROID,
                referer = MUSIC_REFERER,
                music = true
            )
        )

        val DefaultAndroidMusic = Context(
            client = Client(
                clientId = 21,
                clientName = "ANDROID_MUSIC",
                clientVersion = "7.27.52",
                osName = "Android",
                osVersion = "15",
                deviceMake = "Google",
                deviceModel = "Pixel 9 Pro",
                androidSdkVersion = 35,
                userAgent = UserAgents.ANDROID_MUSIC,
                referer = MUSIC_REFERER,
                music = true
            )
        )

        val DefaultIOSMusic = Context(
            client = Client(
                clientId = 26,
                clientName = "IOS_MUSIC",
                clientVersion = "7.27.0",
                osName = "iOS",
                osVersion = "17.5.1.21F90",
                deviceMake = "Apple",
                deviceModel = "iPhone16,2",
                userAgent = UserAgents.IOS_MUSIC,
                referer = MUSIC_REFERER,
                music = true
            )
        )

        val DefaultAndroidCreator = Context(
            client = Client(
                clientId = 14,
                clientName = "ANDROID_CREATOR",
                clientVersion = "23.47.101",
                osName = "Android",
                osVersion = "15",
                deviceMake = "Google",
                deviceModel = "Pixel 9 Pro Fold",
                androidSdkVersion = 35,
                userAgent = UserAgents.ANDROID_CREATOR,
                referer = MUSIC_REFERER,
                music = true
            )
        )

        val DefaultAndroidTestSuite = Context(
            client = Client(
                clientId = 30,
                clientName = "ANDROID_TESTSUITE",
                clientVersion = "1.9",
                osName = "Android",
                osVersion = "15",
                deviceMake = "Google",
                deviceModel = "Pixel 9 Pro",
                androidSdkVersion = 35,
                userAgent = UserAgents.ANDROID_TESTSUITE,
                referer = MUSIC_REFERER,
                music = true
            )
        )

        val DefaultAndroidUnplugged = Context(
            client = Client(
                clientId = 29,
                clientName = "ANDROID_UNPLUGGED",
                clientVersion = "8.49.0",
                osName = "Android",
                osVersion = "15",
                deviceMake = "Google",
                deviceModel = "Pixel 9 Pro",
                androidSdkVersion = 35,
                userAgent = UserAgents.ANDROID_UNPLUGGED,
                referer = MUSIC_REFERER,
                music = true
            )
        )

        val DefaultIPadOS = Context(
            client = Client(
                clientId = 5,
                clientName = "IOS",
                clientVersion = "19.22.3",
                osName = "iPadOS",
                osVersion = "17.7.10.21H450",
                deviceMake = "Apple",
                deviceModel = "iPad7,6",
                userAgent = UserAgents.IPADOS,
                referer = MUSIC_REFERER,
                music = true
            )
        )

        /** ArchiveTune’s working native fallback after WEB_REMIX / Android VR. */
        val DefaultVisionOS = Context(
            client = Client(
                clientId = 101,
                clientName = "VISIONOS",
                clientVersion = "0.1",
                osName = "visionOS",
                osVersion = "1.3.21O771",
                deviceMake = "Apple",
                deviceModel = "RealityDevice14,1",
                userAgent = UserAgents.VISIONOS,
                referer = MUSIC_REFERER,
                music = true
            )
        )

        val DefaultTv = Context(
            client = Client(
                clientId = 7,
                clientName = "TVHTML5",
                clientVersion = "7.20260114.00.00",
                platform = "TV",
                userAgent = UserAgents.TV,
                referer = TV_REFERER,
                music = false
            )
        )

        val DefaultTvEmbedded = Context(
            client = Client(
                clientId = 85,
                clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
                clientVersion = "2.0",
                platform = "TV",
                userAgent = UserAgents.TV_EMBEDDED,
                referer = TV_REFERER,
                music = false
            )
        )

        val DefaultWebPrimary = Context(
            client = Client(
                clientId = 1,
                clientName = "WEB",
                clientVersion = "2.20260114.00.00",
                userAgent = UserAgents.WEB,
                referer = MUSIC_REFERER,
                music = true
            )
        )

        val DefaultWebCreator = Context(
            client = Client(
                clientId = 62,
                clientName = "WEB_CREATOR",
                clientVersion = "1.20260114.00.00",
                userAgent = UserAgents.WEB,
                referer = MUSIC_REFERER,
                music = true
            )
        )
    }
}

/**
 * ArchiveTune’s `PlaybackAuthState.needsServiceIntegrity` policy.  It is shared
 * by player requests and post-resolution GVS URL handling so they cannot drift.
 */
fun Context.Client.requiresServiceIntegrity(): Boolean = when (clientName.uppercase(Locale.US)) {
    "WEB",
    "WEB_REMIX",
    "WEB_CREATOR",
    "MWEB",
    "WEB_EMBEDDED_PLAYER",
    "TVHTML5",
    "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
    "TVHTML5_SIMPLY" -> true

    else -> false
}

/** ArchiveTune sends a signature timestamp only with these client identities. */
internal fun Context.Client.usesSignatureTimestamp(): Boolean = when (clientName.uppercase(Locale.US)) {
    "WEB",
    "WEB_REMIX",
    "WEB_CREATOR",
    "ANDROID",
    "ANDROID_MUSIC",
    "ANDROID_CREATOR",
    "ANDROID_UNPLUGGED",
    "TVHTML5",
    "TVHTML5_SIMPLY_EMBEDDED_PLAYER" -> true

    else -> false
}

internal fun Context.Client.isEmbeddedPlayer(): Boolean =
    clientName.equals("TVHTML5_SIMPLY_EMBEDDED_PLAYER", ignoreCase = true) ||
        clientName.equals("WEB_EMBEDDED_PLAYER", ignoreCase = true)

// @formatter:off
@Suppress("MaximumLineLength")
val validLanguageCodes =
    listOf("af", "az", "id", "ms", "ca", "cs", "da", "de", "et", "en-GB", "en", "es", "es-419", "eu", "fil", "fr", "fr-CA", "gl", "hr", "zu", "is", "it", "sw", "lt", "hu", "nl", "nl-NL", "no", "or", "uz", "pl", "pt-PT", "pt", "ro", "sq", "sk", "sl", "fi", "sv", "bo", "vi", "tr", "bg", "ky", "kk", "mk", "mn", "ru", "sr", "uk", "el", "hy", "iw", "ur", "ar", "fa", "ne", "mr", "hi", "bn", "pa", "gu", "ta", "te", "kn", "ml", "si", "th", "lo", "my", "ka", "am", "km", "zh-CN", "zh-TW", "zh-HK", "ja", "ko")

@Suppress("MaximumLineLength")
val validCountryCodes =
    listOf("DZ", "AR", "AU", "AT", "AZ", "BH", "BD", "BY", "BE", "BO", "BA", "BR", "BG", "KH", "CA", "CL", "HK", "CO", "CR", "HR", "CY", "CZ", "DK", "DO", "EC", "EG", "SV", "EE", "FI", "FR", "GE", "DE", "GH", "GR", "GT", "HN", "HU", "IS", "IN", "ID", "IQ", "IE", "IL", "IT", "JM", "JP", "JO", "KZ", "KE", "KR", "KW", "LA", "LV", "LB", "LY", "LI", "LT", "LU", "MK", "MY", "MT", "MX", "ME", "MA", "NP", "NL", "NZ", "NI", "NG", "NO", "OM", "PK", "PA", "PG", "PY", "PE", "PH", "PL", "PT", "PR", "QA", "RO", "RU", "SA", "SN", "RS", "SG", "SK", "SI", "ZA", "ES", "LK", "SE", "CH", "TW", "TZ", "TH", "TN", "TR", "UG", "UA", "AE", "GB", "US", "UY", "VE", "VN", "YE", "ZW")
// @formatter:on

@Suppress("MaximumLineLength")
object UserAgents {
    const val WEB_REMIX =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    const val WEB =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    const val ANDROID =
        "com.google.android.youtube/21.10.38 (Linux; U; Android 15; en_US; Pixel 9 Pro; Build/AP4A.250205.002; Cronet/132.0.6834.79) gzip"
    const val ANDROID_MUSIC =
        "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 15; en_US; Pixel 9 Pro; Build/AP4A.250205.002; Cronet/132.0.6834.79) gzip"
    const val ANDROID_VR_165 =
        "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
    const val ANDROID_CREATOR =
        "com.google.android.apps.youtube.creator/23.47.101 (Linux; U; Android 15; en_US; Pixel 9 Pro Fold; Build/AP3A.241005.015.A2; Cronet/132.0.6779.0)"
    const val ANDROID_TESTSUITE =
        "com.google.android.youtube/1.9 (Linux; U; Android 15; en_US; Pixel 9 Pro; Build/AP4A.250205.002) gzip"
    const val ANDROID_UNPLUGGED =
        "com.google.android.apps.youtube.unplugged/8.49.0 (Linux; U; Android 15; en_US; Pixel 9 Pro; Build/AP4A.250205.002; Cronet/132.0.6834.79) gzip"
    const val IOS =
        "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)"
    const val IOS_MUSIC =
        "com.google.ios.youtubemusic/7.27.0 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)"
    const val IPADOS =
        "com.google.ios.youtube/19.22.3 (iPad7,6; U; CPU iPadOS 17_7_10 like Mac OS X; en-US)"
    const val VISIONOS =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"
    const val TV =
        "Mozilla/5.0(SMART-TV; Linux; Tizen 4.0.0.2) AppleWebkit/605.1.15 (KHTML, like Gecko) SamsungBrowser/9.2 TV Safari/605.1.15"
    const val TV_EMBEDDED =
        "Mozilla/5.0 (PlayStation; PlayStation 4/12.02) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15"
}
