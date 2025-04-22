package io.cloudshiftdev.gradle.buildcache

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
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

        // TODO - don't hardcode this
        val storeHeaders =
            configuration.storeHeaders.get() + mapOf("x-amz-storage-class" to "ONEZONE_IA")

//        val logging = HttpLoggingInterceptor()
//        logging.setLevel(HttpLoggingInterceptor.Level.HEADERS)

        val httpClient = OkHttpClient.Builder()
          // .addInterceptor(logging)
            .build()

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
