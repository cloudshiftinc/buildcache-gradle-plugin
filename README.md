# CDN Gradle build cache

[![Gradle Plugin Portal Version](https://img.shields.io/gradle-plugin-portal/v/io.cloudshiftdev.buildcache?style=for-the-badge&cacheSeconds=900)](https://plugins.gradle.org/plugin/io.cloudshiftdev.buildcache)

This is a custom Gradle [build cache](https://docs.gradle.org/current/userguide/build_cache.html) implementation which
uses a Content Delivery Network (CDN), such as AWS CloudFront, providing optimization for distributed teams.

The build cache uses a modern HTTP client - OkHttp - to access the CDN, providing additional optimizations such as
HTTP/2.

# Use in your project

## Apply plugin

To use the CDN build cache in your project, apply the plugin in your `settings.gradle` file:

```kotlin
plugins {
    id("io.cloudshitdev.buildcache") version "<latest>"
}
```

```kotlin
buildCache {
    remote<CdnBuildCacheConfiguration> {
        url = uri("https://<your CDN build cache URL>")     // required
        isPush = System.getenv("CI") != null
        credentials {  // optional; use if your CDN requires authentication
            username = providers.gradleProperty("CDN_BUILDCACHE_USERNAME")
            password = providers.gradleProperty("CDN_BUILDCACHE_PASSWORD")
        }
        bearerToken = "my-bearer-token" // optional; use if your CDN requires authentication
        maxCacheEntrySize = 20_000_000L // optional; default is 20MB
        cdnCacheHeader = "X-Cache" // optional; default is "X-Cache"
        storeHeaders = mapOf(
            // optional; default is empty
            "x-amz-storage-class" to "ONEZONE_IA",
        )
    }
}
```

| Configuration setting | Description                                                                        |
|-----------------------|------------------------------------------------------------------------------------|
| `url`                 | Required; The URL of the CDN build cache.                                          |
| `credentials`         | Optional; HTTP BASIC authentication credentials                                    |
| `bearerToken`         | Optional; Bearer token for authentication                                          |
| `maxCacheEntrySize`   | Optional; Maximum size of a cache entry in bytes. Default is 20MB                  |
| `cdnCacheHeader`      | Optional; The name of the header used to identify cache hits. Default is "X-Cache" |
| `storeHeaders`        | Optional; Headers to add to store requests. Default is empty                       |

## CloudFront example

The below CDK snippet creates a CloudFront distribution with a S3 origin, which can be used as a CDN build cache.

```typescript
const bucket = new s3.Bucket(this, 'Bucket', {
    blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
    accessControl: s3.BucketAccessControl.PRIVATE,
    enforceSSL: true,
    encryption: s3.BucketEncryption.S3_MANAGED,
    versioned: true,
    lifecycleRules: [
        {
            id: 'HouseKeeping',
            enabled: true,
            noncurrentVersionExpiration: Duration.days(5),
            abortIncompleteMultipartUploadAfter: Duration.days(1),
        },
        {
            id: 'Expiration',
            enabled: true,
            expiration: Duration.days(30),
        }
    ],
});

const cachePolicy = new cloudfront.CachePolicy(this, 'BuildCachePolicy', {
    cachePolicyName: 'BuildCachePolicy',

    minTtl: Duration.seconds(0),
    defaultTtl: Duration.days(30),
    maxTtl: Duration.days(365),

    cookieBehavior: cloudfront.CacheCookieBehavior.none(),
    headerBehavior: cloudfront.CacheHeaderBehavior.none(),
    queryStringBehavior: cloudfront.CacheQueryStringBehavior.none(),

    enableAcceptEncodingBrotli: true,
    enableAcceptEncodingGzip: true,
});

const cdn = new cloudfront.Distribution(this, 'CDN', {
    enableLogging: true,
    defaultBehavior: {
        origin: origins.S3BucketOrigin.withOriginAccessControl(bucket),
        viewerProtocolPolicy: cloudfront.ViewerProtocolPolicy.HTTPS_ONLY,
        allowedMethods: cloudfront.AllowedMethods.ALLOW_ALL,
        cachedMethods: cloudfront.CachedMethods.CACHE_GET_HEAD,
        compress: true,
        cachePolicy: cachePolicy,

        // TODO: auth lambda
    },
    httpVersion: cloudfront.HttpVersion.HTTP2_AND_3,
    enableIpv6: true,
    minimumProtocolVersion: cloudfront.SecurityPolicyProtocol.TLS_V1_2_2021,
    domainNames: [props.buildCacheResources.cdnDomainName],
    certificate: props.buildCacheResources.cdnCertificate,
});

// return 404 for missing resources instead of 403
bucket.addToResourcePolicy(new iam.PolicyStatement({
    sid: 'ListBucket',
    principals: [new iam.ServicePrincipal('cloudfront.amazonaws.com')],
    actions: ['s3:ListBucket'],
    resources: [bucket.bucketArn],
    conditions: {
        StringEquals: {
            'AWS:SourceArn': cdn.distributionArn,
        },
    },
}));

bucket.addToResourcePolicy(new iam.PolicyStatement({
    sid: 'AllowUploads',
    principals: [new iam.ServicePrincipal('cloudfront.amazonaws.com')],
    actions: ['s3:PutObject'],
    resources: [bucket.arnForObjects('*')],
    conditions: {
        StringEquals: {
            'AWS:SourceArn': cdn.distributionArn,
        },
    },
}));

new route53.ARecord(this, 'AliasRecordV4', {
    zone: props.buildCacheResources.zone,
    recordName: props.buildCacheResources.cdnDomainName,
    target: route53.RecordTarget.fromAlias(new targets.CloudFrontTarget(cdn)),
});

new route53.AaaaRecord(this, 'AliasRecordV6', {
    zone: props.buildCacheResources.zone,
    recordName: props.buildCacheResources.cdnDomainName,
    target: route53.RecordTarget.fromAlias(new targets.CloudFrontTarget(cdn)),
});
```
