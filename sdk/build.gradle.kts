import java.util.Properties
import java.security.MessageDigest

// The official MEGA SDK: libmega.so and the Java bindings, built by scripts/build-sdk.sh.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "nz.mega.sdk"
    compileSdk = 37

    defaultConfig {
        minSdk = 35
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint { abortOnError = true }
}

// src/main/java holds the generated bindings; src/main/glue holds the hand-written additions.
androidComponents {
    onVariants { variant -> variant.sources.java?.addStaticSourceDirectory("src/main/glue") }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.exifinterface)
}

tasks.named("preBuild") {
    val nativeLib = file("src/main/jniLibs/arm64-v8a/libmega.so")
    val provenance = file("src/main/assets/mega-build.properties")
    val sdkVersion = rootProject.file("scripts/sdk-version.sh")
    val patches = rootProject.file("patches")
    doFirst {
        if (!nativeLib.exists()) {
            throw GradleException("libmega.so is missing. Run scripts/bootstrap-sdk.sh and scripts/build-sdk.sh.")
        }
        if (!provenance.exists()) throw GradleException("Native build provenance is missing. Rebuild with scripts/build-sdk.sh.")
        val versions = Properties().apply { provenance.inputStream().use { load(it) } }
        // Every native component, also the ones that the SDK manifest pins (MEGAGLASS-SEC-012): an SDK
        // update that changes one of them must update this map too.
        val expected = mapOf(
            "openssl" to "3.6.5", "curl" to "8.23.0", "libsodium" to "1.0.22", "zlib" to "1.3.2", "sqlite3" to "3.53.4",
            "libmediainfo" to "26.10", "cryptopp" to "8.9.0", "icu" to "74.2", "libuv" to "1.51.0",
            "tinyxml2" to "11.0.0", "zstd" to "1.5.7", "libzen" to "0.4.41",
        )
        if (expected.any { (name, version) -> versions.getProperty(name) != version }) {
            throw GradleException("Native dependencies are out of date. Rebuild with scripts/build-sdk.sh.")
        }
        val sdkCommit = Regex("SDK_COMMIT=\"([0-9a-f]{40})\"").find(sdkVersion.readText())?.groupValues?.get(1)
        if (sdkCommit == null || versions.getProperty("sdkRevision") != sdkCommit) {
            throw GradleException("libmega.so was not built from the pinned MEGA SDK. Run scripts/bootstrap-sdk.sh and scripts/build-sdk.sh.")
        }
        // Same hash as scripts/native-provenance.py: the SDK patches, sorted by name.
        val patchDigest = MessageDigest.getInstance("SHA-256")
        patches.listFiles { f -> f.name.startsWith("mega-sdk-") && f.name.endsWith(".patch") }.orEmpty()
            .sortedBy { it.name }.forEach { patchDigest.update(it.readBytes()) }
        if (patchDigest.digest().joinToString("") { "%02x".format(it) } != versions.getProperty("patchesSha256")) {
            throw GradleException("libmega.so was not built with the current SDK patches. Run scripts/bootstrap-sdk.sh and scripts/build-sdk.sh.")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        nativeLib.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (hash != versions.getProperty("libSha256")) throw GradleException("libmega.so does not match its build provenance. Rebuild with scripts/build-sdk.sh.")
    }
}
