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

public class S3BuildCacheConfiguration(private val config: CdnBuildCacheConfiguration) {
    public sealed class StorageClass(public val value: String) {
        public data object Standard : StorageClass("STANDARD")

        public data object StandardInfrequentAccess : StorageClass("STANDARD_IA")

        public data object ReducedRedundancy : StorageClass("REDUCED_REDUNDANCY")

        public data object IntelligentTiering : StorageClass("INTELLIGENT_TIERING")

        public data object OneZoneInfrequentAccess : StorageClass("ONEZONE_IA")

        public data object Glacier : StorageClass("GLACIER")

        public data object GlacierInstantRetrieval : StorageClass("GLACIER_IR")

        public data object DeepArchive : StorageClass("DEEP_ARCHIVE")

        public data object ExpressOneZone : StorageClass("EXPRESS_ONEZONE")
    }

    public fun storageClass(storageClass: StorageClass) {
        config.storeHeaders.put("x-amz-storage-class", storageClass.value)
    }

    public fun metadata(key: String, value: String) {
        config.storeHeaders.put("x-amz-meta-$key", value)
    }
}

/**
 * Configuration when using CloudFront with S3 as the build cache. Allows for setting the storage
 * class and object metadata.
 */
public fun CdnBuildCacheConfiguration.s3(action: Action<S3BuildCacheConfiguration>) {
    val s3Config = S3BuildCacheConfiguration(this)
    action.execute(s3Config)
}

/**
 * Common configuration when using CloudFront with S3 as the build cache. Sets the S3 storage class
 * to One Zone Infrequent Access.
 */
public fun CdnBuildCacheConfiguration.awsCloudFrontWithS3() {
    s3 { storageClass(S3BuildCacheConfiguration.StorageClass.OneZoneInfrequentAccess) }
}
