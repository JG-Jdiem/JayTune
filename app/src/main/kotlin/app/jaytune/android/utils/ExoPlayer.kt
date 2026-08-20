@file:OptIn(UnstableApi::class)

package app.jaytune.android.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import java.io.EOFException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import kotlin.math.pow

class RangeHandlerDataSourceFactory(private val parent: DataSource.Factory) : DataSource.Factory {
    class Source(private val parent: DataSource) : DataSource by parent {
        override fun open(dataSpec: DataSpec) = runCatching {
            parent.open(dataSpec)
        }.getOrElse { e ->
            if (
                e.findCause<EOFException>() != null ||
                e.findCause<InvalidResponseCodeException>()?.responseCode == 416
            ) parent.open(
                dataSpec
                    .buildUpon()
                    .setHttpRequestHeaders(
                        dataSpec.httpRequestHeaders.filter {
                            it.key.equals("range", ignoreCase = true)
                        }
                    )
                    .setLength(C.LENGTH_UNSET.toLong())
                    .build()
            )
            else throw e
        }
    }

    override fun createDataSource() = Source(parent.createDataSource())
}

class CatchingDataSourceFactory(
    private val parent: DataSource.Factory,
    private val onError: ((Throwable) -> Unit)?
) : DataSource.Factory {
    inner class Source(private val parent: DataSource) : DataSource by parent {
        override fun open(dataSpec: DataSpec) = runCatching {
            parent.open(dataSpec)
        }.getOrElse { ex ->
            ex.printStackTrace()

            if (ex is PlaybackException) throw ex
            else throw PlaybackException(
                /* message = */ "Unknown playback error",
                /* cause = */ ex,
                /* errorCode = */ PlaybackException.ERROR_CODE_UNSPECIFIED
            ).also { onError?.invoke(it) }
        }
    }

    override fun createDataSource() = Source(parent.createDataSource())
}

fun DataSource.Factory.handleRangeErrors(): DataSource.Factory = RangeHandlerDataSourceFactory(this)
fun DataSource.Factory.handleUnknownErrors(
    onError: ((Throwable) -> Unit)? = null
): DataSource.Factory = CatchingDataSourceFactory(
    parent = this,
    onError = onError
)

class FallbackDataSourceFactory(
    private val upstream: DataSource.Factory,
    private val fallback: DataSource.Factory
) : DataSource.Factory {
    inner class Source(private val parent: DataSource) : DataSource by parent {
        override fun open(dataSpec: DataSpec) = runCatching {
            parent.open(dataSpec)
        }.getOrElse { ex ->
            ex.printStackTrace()

            runCatching {
                fallback.createDataSource().open(dataSpec)
            }.getOrElse { fallbackEx ->
                fallbackEx.printStackTrace()

                throw ex
            }
        }
    }

    override fun createDataSource() = Source(upstream.createDataSource())
}

fun DataSource.Factory.withFallback(
    fallbackFactory: DataSource.Factory
): DataSource.Factory = FallbackDataSourceFactory(this, fallbackFactory)

fun DataSource.Factory.withFallback(
    context: Context,
    resolver: ResolvingDataSource.Resolver
) = withFallback(ResolvingDataSource.Factory(DefaultDataSource.Factory(context), resolver))

class RetryingDataSourceFactory(
    private val parentFactory: DataSource.Factory,
    private val maxRetries: Int,
    private val printStackTrace: Boolean,
    private val exponential: Boolean,
    private val predicate: (Throwable) -> Boolean
) : DataSource.Factory {
    /**
     * A failed Media3 source may remain open internally. Each retry must therefore
     * create a new complete source chain, so ResolvingDataSource can resolve a
     * fresh Googlevideo URL instead of reopening an invalid CacheDataSource.
     */
    inner class Source : DataSource {
        private val transferListeners = mutableSetOf<TransferListener>()
        private var parent = parentFactory.createDataSource()

        override fun addTransferListener(transferListener: TransferListener) {
            transferListeners += transferListener
            parent.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            var retryCount = 0

            while (true) {
                if (retryCount > 0) Log.d(TAG, "Retry $retryCount of $maxRetries fetching datasource")

                @Suppress("TooGenericExceptionCaught")
                try {
                    return parent.open(dataSpec)
                } catch (ex: Throwable) {
                    if (printStackTrace) Log.e(
                        /* tag = */ TAG,
                        /* msg = */ "Exception caught by retry mechanism",
                        /* tr = */ ex
                    )

                    if (!predicate(ex)) {
                        Log.e(TAG, "Retry policy declined retry, throwing the exception...")
                        throw ex
                    }

                    if (retryCount >= maxRetries) {
                        Log.e(TAG, "Max retries $maxRetries exceeded, throwing the exception...")
                        throw ex
                    }

                    runCatching { parent.close() }
                    parent = parentFactory.createDataSource().also { freshSource ->
                        transferListeners.forEach(freshSource::addTransferListener)
                    }

                    retryCount++
                    val time = if (exponential) 1000L * 2.0.pow(retryCount - 1).toLong() else 2500L
                    Log.d(TAG, "Retry policy accepted retry, sleeping for $time milliseconds")
                    Thread.sleep(time)
                }
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int) = parent.read(buffer, offset, length)

        override fun getUri(): Uri? = parent.uri

        override fun getResponseHeaders(): Map<String, List<String>> = parent.responseHeaders

        override fun close() = parent.close()
    }

    override fun createDataSource() = Source()
}

inline fun <reified T : Throwable> DataSource.Factory.retryIf(
    maxRetries: Int = 5,
    printStackTrace: Boolean = false,
    exponential: Boolean = true
) = retryIf(maxRetries, printStackTrace, exponential) { ex -> ex.findCause<T>() != null }

private const val TAG = "DataSource.Factory"

fun DataSource.Factory.retryIf(
    maxRetries: Int = 5,
    printStackTrace: Boolean = false,
    exponential: Boolean = true,
    predicate: (Throwable) -> Boolean
): DataSource.Factory = RetryingDataSourceFactory(this, maxRetries, printStackTrace, exponential, predicate)

val Cache.asDataSource get() = CacheDataSource.Factory().setCache(this)

private const val ANDROID_VR_USER_AGENT =
    "com.google.android.apps.youtube.vr.oculus/1.65.10 " +
        "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

/**
 * Googlevideo validates the complete native-client request profile. Applying it
 * in an OkHttp interceptor happens after Media3 has built each request, so it
 * also covers byte-range reads, redirects, and retries.
 */
private val youtubeMediaOkHttpClient by lazy {
    OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            val client = request.url.queryParameter("c").orEmpty()

            if (client.startsWith("ANDROID_VR", ignoreCase = true)) {
                chain.proceed(
                    request.newBuilder()
                        .header("User-Agent", ANDROID_VR_USER_AGENT)
                        .removeHeader("Origin")
                        .removeHeader("Referer")
                        .build()
                )
            } else {
                chain.proceed(request)
            }
        }
        .build()
}

val Context.defaultDataSource
    get() = DefaultDataSource.Factory(
        this,
        OkHttpDataSource.Factory(youtubeMediaOkHttpClient)
    )
