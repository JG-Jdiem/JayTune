package app.jaytune.providers.innertube.models.bodies

import kotlinx.serialization.Serializable

@Serializable
data class AndroidVrPlayerBody(
    val context: Context,
    val videoId: String,
    val playlistId: String? = null,
    val params: String? = null
) {
    @Serializable
    data class Context(
        val client: Client
    ) {
        @Serializable
        data class Client(
            val clientName: String = "ANDROID_VR",
            val clientVersion: String = "1.65.10",
            val deviceMake: String = "Google",
            val deviceModel: String = "Pixel 8",
            val osName: String = "Android",
            val osVersion: String = "14",
            val hl: String = "en",
            val gl: String = "US",
            val visitorData: String? = null
        )
    }

    companion object {
        fun create(videoId: String, visitorData: String? = null) = AndroidVrPlayerBody(
            context = Context(
                client = Context.Client(visitorData = visitorData)
            ),
            videoId = videoId
        )
    }
}
