import java.nio.charset.StandardCharsets
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `kotlin-dsl`
    signing
    alias(libs.plugins.publish)
    alias(libs.plugins.spotless)
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.17")
    implementation("com.squareup.okhttp3:logging-interceptor:5.0.0-alpha.17")
    implementation("com.google.guava:guava:33.4.8-jre")
}

gradlePlugin {
    website = "https://github.com/cloudshiftinc/buildcache-gradle-plugin"
    vcsUrl = "https://github.com/cloudshiftinc/buildcache-gradle-plugin"
    plugins {
        create("buildcache") {
            id = "io.cloudshiftdev.buildcache"
            displayName = "Plugin providing BuildCache S3/CloudFront implementation"
            description = "Settings plugin providing BuildCache S3/CloudFront implementation"
            implementationClass = "io.cloudshiftdev.gradle.buildcache.BuildCachePlugin"
            tags = listOf("buildcache", "aws", "s3", "cloudfront", "cdn")
        }
    }
}

signing {
    val signingKey = findProperty("SIGNING_KEY") as? String
    val signingPwd = findProperty("SIGNING_PWD") as? String
    useInMemoryPgpKeys(signingKey, signingPwd)
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        val additionalArgs =
            listOfNotNull(
                    "jdk-release=17", // https://jakewharton.com/kotlins-jdk-release-compatibility-flag/
                    "jsr305=strict",
                )
                .map { "-X$it" }
        freeCompilerArgs.addAll(additionalArgs)
    }
}

val ktfmtVersion = ktfmtVersion()

spotless {
    encoding = StandardCharsets.UTF_8

    kotlinGradle { ktfmt(ktfmtVersion).kotlinlangStyle() }

    kotlin { ktfmt(ktfmtVersion).kotlinlangStyle() }
}

internal fun Project.ktfmtVersion(): String {
    val resourceUri = this::class.java.getResource("/buildcache-plugin/ktfmt-version.txt")
    return resourceUri?.let { resources.text.fromUri(it).asString() } ?: "0.52"
}

tasks {
    val persistKtfmtVersion by registering {
        inputs.property("ktfmtVersion", libs.ktfmt)
        outputs.files(layout.buildDirectory.file("ktfmt-version.txt"))
        doLast {
            outputs.files.singleFile.writeText(
                inputs.properties["ktfmtVersion"].toString().substringAfterLast(":")
            )
        }
    }

    named<ProcessResources>("processResources") {
        from(persistKtfmtVersion) { into("buildcache-plugin") }
    }

    withType<ValidatePlugins>().configureEach {
        enableStricterValidation = true
        failOnWarning = true
    }

    named<KotlinCompile>("compileKotlin") {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
            apiVersion = KotlinVersion.KOTLIN_2_0
            languageVersion = KotlinVersion.KOTLIN_2_0
        }
    }

    withType<PublishToMavenRepository>().configureEach {
        onlyIf {
            when {
                System.getenv("CI") != null ->
                    when {
                        version.toString().endsWith("SNAPSHOT") ->
                            repository.name.endsWith("Snapshot")
                        else -> repository.name.endsWith("Release")
                    }

                else -> repository.name.endsWith("Local")
            }
        }
    }
}

testing {
    suites {
        val test by
            getting(JvmTestSuite::class) {
                useJUnitJupiter()
                dependencies {
                    implementation(platform(libs.kotest.bom))
                    implementation(libs.kotest.assertions.core)
                    implementation(libs.kotest.assertions.json)
                    implementation(libs.kotest.framework.datatest)
                    implementation(libs.kotest.property)
                    implementation(libs.kotest.runner.junit5)
                    implementation(libs.mockk)
                }
                targets {
                    all {
                        testTask.configure {
                            outputs.upToDateWhen { false }
                            testLogging {
                                events =
                                    setOf(
                                        TestLogEvent.FAILED,
                                        TestLogEvent.PASSED,
                                        TestLogEvent.SKIPPED,
                                        TestLogEvent.STANDARD_OUT,
                                        TestLogEvent.STANDARD_ERROR,
                                    )
                                exceptionFormat = TestExceptionFormat.FULL
                                showExceptions = true
                                showCauses = true
                                showStackTraces = true
                            }
                        }
                    }
                }
            }
    }
}
