package app.jaytune.providers.innertube.models

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class Thumbnail(
    val url: String,
    val height: Int?,
    val width: Int?
) {
    fun size(size: Int): String {
        val logger = LoggerFactory.getLogger("Thumbnail")
        logger.info("URL received: $url")
        return when {
            url.startsWith("https://lh3.googleusercontent.com") -> "$url-w$size-h$size"
            url.startsWith("https://yt3.ggpht.com") -> "$url-s$size"
            url.startsWith("https://i.ytimg.com") -> {
                val result = url.replace("mqdefault", "hqdefault")
                    .replace("sddefault", "hqdefault")
                    .replace("/default.jpg", "/hqdefault.jpg")
                logger.info("Converted URL: $result")
                result
            }
            else -> {
                logger.info("URL not matched, returning as-is: $url")
                url
            }
        }
    }
}
