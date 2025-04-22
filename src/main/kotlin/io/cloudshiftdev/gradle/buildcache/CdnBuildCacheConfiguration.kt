package io.cloudshiftdev.gradle.buildcache

import java.net.URI
import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.caching.configuration.AbstractBuildCache
import org.gradle.kotlin.dsl.newInstance

public abstract class CdnBuildCacheConfiguration
@Inject
constructor(private val objects: ObjectFactory) : AbstractBuildCache() {
    init {
        cdnCacheHeader.convention("X-Cache")
        maxCacheEntrySize.convention(20_000_000L)
    }

    @get:Input public abstract val url: Property<URI>

    internal abstract var credentials: CdnBuildCacheCredentials?

    @get:Input public abstract val bearerToken: Property<String>

    @get:Input public abstract val maxCacheEntrySize: Property<Long>

    @get:Input public abstract val cdnCacheHeader: Property<String>

    @get:Input public abstract val storeHeaders: MapProperty<String, String>

    public fun credentials(action: Action<CdnBuildCacheCredentials>) {
        val creds = objects.newInstance<CdnBuildCacheCredentials>()
        action.execute(creds)
        this.credentials = creds
    }
}

public abstract class CdnBuildCacheCredentials {
    @get:Input public abstract val username: Property<String>

    @get:Input public abstract val password: Property<String>
}
