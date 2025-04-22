package io.cloudshiftdev.gradle.buildcache

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.kotlin.dsl.registerBuildCacheService

public abstract class BuildCachePlugin : Plugin<Settings> {
    override fun apply(settings: Settings): Unit =
        settings.run {
            buildCache.registerBuildCacheService(
                CdnBuildCacheConfiguration::class,
                CdnBuildCacheServiceFactory::class,
            )
        }
}
