package io.cloudshiftdev.gradle.buildcache

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.gradle.api.logging.Logging
import org.gradle.caching.BuildCacheService
import org.gradle.caching.BuildCacheServiceFactory

public class CdnBuildCacheServiceFactory : BuildCacheServiceFactory<CdnBuildCacheConfiguration> {
    override fun createBuildCacheService(
        configuration: CdnBuildCacheConfiguration,
        describer: BuildCacheServiceFactory.Describer,
    ): BuildCacheService {

        val baseUrl =
            configuration.url.get().toHttpUrlOrNull()
                ?: error("Invalid URL: ${configuration.url.get()}")

        val credentials =
            configuration.credentials?.let {
                CdnBuildCacheService.Credentials(
                    username = it.username.get(),
                    password = it.password.get(),
                )
            }
        val bearerToken = configuration.bearerToken.orNull
        val cdnCacheHeader = configuration.cdnCacheHeader.get()
        val maxCacheEntrySize = configuration.maxCacheEntrySize.get()

        val storeHeaders = configuration.storeHeaders.get()

        val loggingInterceptor = HttpLoggingInterceptor(OkHttpLogger())
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.HEADERS)
        loggingInterceptor.redactHeader("Authorization")

        val httpClient = OkHttpClient.Builder().addInterceptor(loggingInterceptor).build()

        describer.apply {
            type("Content Delivery Network (CDN)")
            config("url", baseUrl.toString())
            config("cdnCacheHeader", cdnCacheHeader)
            config("maxCacheEntrySize", maxCacheEntrySize.toString())
            config("storeHeaders", storeHeaders.toString())
        }

        return CdnBuildCacheService(
            httpClient = httpClient,
            baseUrl = baseUrl,
            cdnBuildCacheCredentials = credentials,
            bearerToken = bearerToken,
            cdnCacheHeader = cdnCacheHeader,
            maxCacheEntrySize = maxCacheEntrySize,
            storeHeaders,
        )
    }
}

private class OkHttpLogger : HttpLoggingInterceptor.Logger {
    private val logger = Logging.getLogger(OkHttpLogger::class.java)

    override fun log(message: String) {
        logger.debug(message)
    }
}
