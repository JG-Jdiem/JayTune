package app.jaytune.providers.innertube.models.bodies

import app.jaytune.providers.innertube.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class PlayerBody(
    val context: Context = Context.DefaultAndroidMusic,
    val videoId: String,
    val playlistId: String? = null,
    val params: String? = null,
    val cpn: String? = null,
    // YouTube expects these exact boolean fields. ArchiveTune sends the same
    // shape; the old string racyCheckOn field was ignored by InnerTube.
    val racyCheckOk: Boolean = true,
    val contentCheckOk: Boolean = true,
    val playbackContext: PlaybackContext? = null,
    val serviceIntegrityDimensions: ServiceIntegrityDimensions? = null
) {
    @Serializable
    data class ServiceIntegrityDimensions(
        val poToken: String
    )

    @Serializable
    data class PlaybackContext(
        val contentPlaybackContext: ContentPlaybackContext? = null
    ) {
        @Serializable
        data class ContentPlaybackContext(
            val signatureTimestamp: Int? = null
        )
    }
}
