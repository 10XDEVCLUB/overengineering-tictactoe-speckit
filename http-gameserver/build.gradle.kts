import java.util.*
import java.util.Base64

plugins {
    id("buildlogic.java-library-conventions")
    id("com.vanniktech.maven.publish")
    id("signing")
    id("me.champeau.jmh") version "0.7.2"
    application
}

application {
    mainClass.set("org.xxdc.oss.example.HttpGameServer")
}

group = "org.xxdc.oss.example"

dependencies {
    // JDK9: Platform Logging (Third-Party)
    // -> JDK API -> SLF4J -> Logback
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.6")
    testRuntimeOnly("org.slf4j:slf4j-api:2.0.13")
    testRuntimeOnly("org.slf4j:slf4j-jdk-platform-logging:2.0.13")

    implementation(project(":api"))

    jmhImplementation("org.openjdk.jmh:jmh-core:1.37")
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

jmh {
    jvmArgs.add("--enable-preview")
    jvmArgs.add("-XX:+UseZGC")
    fork = 1
    warmupIterations = 2
    iterations = 5
}

val enablePreviewFeatures = true

val collectorArgs = listOf(
    "-XX:+UseZGC"
)

tasks.named<Test>("test") {
    jvmArgs = if (enablePreviewFeatures) {
        listOf("--enable-preview") + collectorArgs
    } else {
        collectorArgs
    }
}

if (enablePreviewFeatures) {
    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(listOf("--enable-preview"))
    }

    tasks.withType<JavaExec>().configureEach {
        jvmArgs("--enable-preview")
    }

    tasks.withType<Javadoc>() {
        (options as StandardJavadocDocletOptions).apply {
            addBooleanOption("-enable-preview", true)
            source = "25"
        }
    }
}

// Publishing
mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    val skipSigning = ((findProperty("skipSigning") as String?)?.toBoolean() == true)
    val signingKey = (findProperty("signingInMemoryKey") ?: findProperty("signing.key")) as String?
    if (!skipSigning && signingKey != null) {
        signAllPublications()
    }

    coordinates(
        project.group as String?,
        "tictactoe-http-gameserver",
        project.version as String?
    )
    pom {
        name.set("tictactoe-http-gameserver")
        description.set("An Over-Engineered Tic Tac Toe Game Server (HTTP/3)")
    }
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/briancorbinxyz/overengineering-tictactoe")
            credentials {
                username = findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
                password = findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

signing {
    val skipSigning = ((findProperty("skipSigning") as String?)?.toBoolean() == true)
    if (skipSigning) {
        logger.lifecycle("skipSigning=true; skipping signing for publications.")
        return@signing
    }

    val inMemoryKeyBase64 = findProperty("signingInMemoryKeyBase64") as String?
    val inMemoryKeyPlain = (findProperty("signingInMemoryKey") ?: findProperty("signing.key")) as String?
    val inMemoryKey = when {
        !inMemoryKeyBase64.isNullOrBlank() -> String(Base64.getDecoder().decode(inMemoryKeyBase64), Charsets.UTF_8)
        else -> inMemoryKeyPlain
    }
    val inMemoryKeyId = findProperty("signingInMemoryKeyId") as String?
    val inMemoryKeyPassword = (findProperty("signingInMemoryKeyPassword") ?: findProperty("signing.password")) as String?

    if (project.hasProperty("useGpg")) {
        useGpgCmd()
        sign(publishing.publications)
    } else if (!inMemoryKey.isNullOrBlank()) {
        if (!inMemoryKeyId.isNullOrBlank()) {
            useInMemoryPgpKeys(inMemoryKeyId, inMemoryKey, inMemoryKeyPassword)
            sign(publishing.publications)
        } else {
            throw GradleException("In-memory signing key provided but signingInMemoryKeyId is missing. Provide signingInMemoryKeyId or set -PuseGpg or -PskipSigning=true.")
        }
    } else {
        throw GradleException("Signing is required but not configured. Provide -PuseGpg or in-memory signing properties (signingInMemoryKeyBase64 or signingInMemoryKey/signing.key, signingInMemoryKeyId, and signingInMemoryKeyPassword/signing.password), or set -PskipSigning=true to skip.")
    }
}
