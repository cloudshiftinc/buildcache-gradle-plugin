package io.cloudshiftdev.gradle.buildcache

import com.google.common.base.Throwables
import io.cloudshiftdev.gradle.buildcache.util.CountingInputStream
import java.text.DecimalFormat
import java.util.*
import kotlin.math.ln
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.TimeSource
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.gradle.caching.BuildCacheEntryReader
import org.gradle.caching.BuildCacheEntryWriter
import org.gradle.caching.BuildCacheException
import org.gradle.caching.BuildCacheKey
import org.gradle.caching.BuildCacheService

internal class CdnBuildCacheService(
    private val httpClient: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val cdnBuildCacheCredentials: Credentials?,
    private val bearerToken: String?,
    private val cdnCacheHeader: String,
    private val maxCacheEntrySize: Long,
    private val storeHeaders: Map<String, String>,
) : BuildCacheService {

    private companion object {
        val BuildCacheContentType = "application/vnd.gradle.build-cache-artifact.v2".toMediaType()
        val AcceptHeaderValue = listOf(BuildCacheContentType, "*/*".toMediaType()).joinToString(",")
        val logger = org.gradle.api.logging.Logging.getLogger(CdnBuildCacheService::class.java)
    }

    data class Credentials(val username: String, val password: String)

    private val timeSource = TimeSource.Monotonic
    private val tracker = MetricsTracker()

    override fun load(key: BuildCacheKey, reader: BuildCacheEntryReader): Boolean {

        val url = key.toUrl()

        val request =
            Request.Builder().url(url).header("Accept", AcceptHeaderValue).addCredentials().build()

        val mark = timeSource.markNow()
        return try {
            httpClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val countingInputStream = CountingInputStream(response.body.byteStream())
                        reader.readFrom(countingInputStream)
                        val elapsed = timeSource.markNow() - mark
                        true.also {
                            tracker.recordBuildCacheAction(
                                BuildCacheAction.Load(
                                    url,
                                    LoadStatus.Hit(
                                        bytes = countingInputStream.count,
                                        duration = elapsed,
                                        cdnCacheStatus =
                                            CdnCacheStatus.from(response.header(cdnCacheHeader)),
                                    ),
                                )
                            )
                        }
                    }

                    response.code == 404 -> {
                        false.also {
                            tracker.recordBuildCacheAction(
                                BuildCacheAction.Load(
                                    url,
                                    LoadStatus.Miss(
                                        "${response.code} ${response.message} ${response.body.string()}"
                                    ),
                                )
                            )
                        }
                    }

                    else ->
                        throw BuildCacheException(
                            "${response.code} ${response.message} ${response.body.string()}"
                        )
                }
            }
        } catch (e: Exception) {
            tracker.recordBuildCacheAction(
                BuildCacheAction.Load(
                    url,
                    LoadStatus.Failure(Throwables.getRootCause(e).toString()),
                )
            )
            throw e
        }
    }

    override fun store(key: BuildCacheKey, writer: BuildCacheEntryWriter) {

        val url = key.toUrl()

        if (writer.size > maxCacheEntrySize) {
            tracker.recordBuildCacheAction(
                BuildCacheAction.Store(url, StoreStatus.TooLarge(writer.size))
            )
            return
        }

        val requestBody =
            object : RequestBody() {
                override fun contentLength(): Long = writer.size

                override fun contentType() = BuildCacheContentType

                override fun writeTo(sink: BufferedSink) {
                    writer.writeTo(sink.outputStream())
                }
            }

        val request =
            Request.Builder()
                .url(url)
                .put(requestBody)
                .addHeaders(storeHeaders)
                .addCredentials()
                .build()

        val mark = timeSource.markNow()
        try {
            httpClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        tracker.recordBuildCacheAction(
                            BuildCacheAction.Store(
                                url,
                                StoreStatus.Success(
                                    bytes = writer.size,
                                    duration = timeSource.markNow() - mark,
                                ),
                            )
                        )
                    }

                    else ->
                        throw BuildCacheException(
                            "${response.code} ${response.message} ${response.body.string()}"
                        )
                }
            }
        } catch (e: Exception) {
            tracker.recordBuildCacheAction(
                BuildCacheAction.Store(
                    url,
                    StoreStatus.Failure(Throwables.getRootCause(e).toString()),
                )
            )
            throw e
        }
    }

    override fun close() {
        if (logger.isInfoEnabled) {
            tracker.actions().forEach { action -> logger.info("{}", action) }
        }

        val metrics = tracker.summarize()

        val loadMetrics = metrics.cacheLoadMetrics
        val loadHitPct =
            if (loadMetrics.loadRequests > 0) {
                loadMetrics.cacheHits.toDouble() / loadMetrics.loadRequests * 100
            } else {
                0.0
            }

        val cdnHitPct =
            if (loadMetrics.cacheHits > 0) {
                loadMetrics.cacheHitMetrics.cdnHits.toDouble() / loadMetrics.cacheHits * 100
            } else {
                0.0
            }

        fun Double.format(scale: Int) = "%.${scale}f".format(this)
        fun Long.formatByteCount(si: Boolean = true): String {
            val unit = if (si) 1000 else 1024
            if (this < unit) return "$this B"
            val exp = (ln(this.toDouble()) / ln(unit.toDouble())).toInt()
            val pre = (if (si) "kMGTPE" else "KMGTPE")[exp - 1] + if (si) "" else "i"
            val formattedValue =
                DecimalFormat("#.##").format(this / unit.toDouble().pow(exp.toDouble()))
            return "$formattedValue ${pre}B"
        }

        logger.lifecycle(
            "CDN build cache load metrics: ${loadHitPct.format(1)}% ${loadMetrics.cacheMisses} misses, ${loadMetrics.cacheHits} hits (${loadMetrics.cacheHitMetrics.bytes.formatByteCount()}, ${loadMetrics.cacheHitMetrics.duration.inWholeSeconds}s; CDN: ${cdnHitPct.format(1)}% ${loadMetrics.cacheHitMetrics.cdnHits} hits, ${loadMetrics.cacheHitMetrics.cdnMisses} misses, ${loadMetrics.cacheHitMetrics.cdnUnknown} unknown)"
        )

        val storeMetrics = metrics.cacheStoreMetrics
        logger.lifecycle(
            "CDN build cache store metrics: ${storeMetrics.storeRequests} requests, ${storeMetrics.storedBytes.formatByteCount()}, ${storeMetrics.storeDuration.inWholeSeconds}s; skipped too large: ${storeMetrics.skippedTooLarge} (${storeMetrics.skippedTooLargeBytes} bytes)"
        )
    }

    private fun BuildCacheKey.toUrl(): HttpUrl =
        baseUrl.newBuilder().addPathSegment(hashCode).build()

    private fun Request.Builder.addCredentials(): Request.Builder {
        cdnBuildCacheCredentials?.let {
            header("Authorization", okhttp3.Credentials.basic(it.username, it.password))
        }
        bearerToken?.let { header("Authorization", "Bearer $it") }
        return this
    }
}

private fun Request.Builder.addHeaders(storeHeaders: Map<String, String>): Request.Builder {
    storeHeaders.forEach { (key, value) -> addHeader(key, value) }
    return this
}

private sealed class CdnCacheStatus {
    data object Hit : CdnCacheStatus()

    data object Miss : CdnCacheStatus()

    data object Unknown : CdnCacheStatus()

    companion object {
        fun from(value: String?): CdnCacheStatus {
            if (value == null) return Unknown
            if (value.lowercase().contains("hit")) return Hit
            if (value.lowercase().contains("miss")) return Miss
            return Unknown
        }
    }
}

private sealed class LoadStatus {
    data class Hit(val bytes: Long, val duration: Duration, val cdnCacheStatus: CdnCacheStatus) :
        LoadStatus()

    data class Miss(val message: String) : LoadStatus()

    data class Failure(val errorMessage: String) : LoadStatus()
}

private sealed class StoreStatus {
    data class Success(val bytes: Long, val duration: Duration) : StoreStatus()

    data class TooLarge(val bytes: Long) : StoreStatus()

    data class Failure(val errorMessage: String) : StoreStatus()
}

private sealed class BuildCacheAction {
    abstract val url: HttpUrl

    data class Load(override val url: HttpUrl, val loadStatus: LoadStatus) : BuildCacheAction()

    data class Store(override val url: HttpUrl, val storeStatus: StoreStatus) : BuildCacheAction()
}

private class MetricsTracker {
    private val actions = Collections.synchronizedList(mutableListOf<BuildCacheAction>())

    fun recordBuildCacheAction(action: BuildCacheAction) {
        actions.add(action)
    }

    fun actions() = actions.toList()

    fun summarize(): CacheMetrics {
        val loadActions = actions.filterIsInstance<BuildCacheAction.Load>()

        val loadHits = loadActions.filter { it.loadStatus is LoadStatus.Hit }
        val loadMisses = loadActions.count { it.loadStatus is LoadStatus.Miss }

        val cacheHitMetrics =
            loadHits.fold(CacheHitMetrics(0, Duration.ZERO, 0, 0, 0)) { acc, action ->
                val loadStatus = action.loadStatus as LoadStatus.Hit
                CacheHitMetrics(
                    bytes = acc.bytes + loadStatus.bytes,
                    duration = acc.duration + loadStatus.duration,
                    cdnHits =
                        if (loadStatus.cdnCacheStatus is CdnCacheStatus.Hit) acc.cdnHits + 1
                        else acc.cdnHits,
                    cdnMisses =
                        if (loadStatus.cdnCacheStatus is CdnCacheStatus.Miss) acc.cdnMisses + 1
                        else acc.cdnMisses,
                    cdnUnknown =
                        if (loadStatus.cdnCacheStatus is CdnCacheStatus.Unknown) acc.cdnUnknown + 1
                        else acc.cdnUnknown,
                )
            }

        val cacheLoadMetrics =
            CacheLoadMetrics(cacheMisses = loadMisses, cacheHitMetrics = cacheHitMetrics)

        val storeActions = actions.filterIsInstance<BuildCacheAction.Store>()

        val cacheStoreMetrics =
            storeActions.fold(CacheStoreMetrics(0, 0, Duration.ZERO, 0, 0L)) { acc, action ->
                val storeStatus = action.storeStatus
                when (storeStatus) {
                    is StoreStatus.Success -> {
                        CacheStoreMetrics(
                            storeRequests = acc.storeRequests + 1,
                            storedBytes = acc.storedBytes + storeStatus.bytes,
                            storeDuration = acc.storeDuration + storeStatus.duration,
                            skippedTooLarge = acc.skippedTooLarge,
                            skippedTooLargeBytes = acc.skippedTooLargeBytes,
                        )
                    }

                    is StoreStatus.TooLarge -> {
                        CacheStoreMetrics(
                            storeRequests = acc.storeRequests,
                            storedBytes = acc.storedBytes,
                            storeDuration = acc.storeDuration,
                            skippedTooLarge = acc.skippedTooLarge + 1,
                            skippedTooLargeBytes = acc.skippedTooLargeBytes + storeStatus.bytes,
                        )
                    }

                    is StoreStatus.Failure -> {
                        CacheStoreMetrics(
                            storeRequests = acc.storeRequests,
                            storedBytes = acc.storedBytes,
                            storeDuration = acc.storeDuration,
                            skippedTooLarge = acc.skippedTooLarge,
                            skippedTooLargeBytes = acc.skippedTooLargeBytes,
                        )
                    }
                }
            }

        return CacheMetrics(cacheLoadMetrics, cacheStoreMetrics)
    }

    data class CacheMetrics(
        val cacheLoadMetrics: CacheLoadMetrics,
        val cacheStoreMetrics: CacheStoreMetrics,
    )

    data class CacheLoadMetrics(val cacheMisses: Int, val cacheHitMetrics: CacheHitMetrics) {
        val cacheHits = cacheHitMetrics.hits
        val loadRequests = cacheHits + cacheMisses
    }

    data class CacheHitMetrics(
        val bytes: Long,
        val duration: Duration,
        val cdnHits: Int,
        val cdnMisses: Int,
        val cdnUnknown: Int,
    ) {
        val hits = cdnHits + cdnMisses + cdnUnknown
    }

    data class CacheStoreMetrics(
        val storeRequests: Int,
        val storedBytes: Long,
        val storeDuration: Duration,
        val skippedTooLarge: Int,
        val skippedTooLargeBytes: Long,
    )
}
