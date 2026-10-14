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

    lint { abortOnError = false }
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
    doFirst {
        if (!nativeLib.exists()) {
            throw GradleException("libmega.so is missing. Run scripts/bootstrap-sdk.sh and scripts/build-sdk.sh.")
        }
    }
}
