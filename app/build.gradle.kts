import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing: keystore.properties (not in git) names the keystore and the Keychain items
// that hold its passwords. See scripts/make-release-key.sh.
val signingProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

fun keychain(service: String): String =
    providers.exec { commandLine("security", "find-generic-password", "-s", service, "-w") }
        .standardOutput.asText.get().trim()

android {
    namespace = "app.megaglass.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.megaglass.android"
        minSdk = 35
        targetSdk = 37
        versionCode = 5
        versionName = "0.1.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (signingProps != null) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = keychain(signingProps.getProperty("storePasswordKeychain"))
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = keychain(signingProps.getProperty("keyPasswordKeychain"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a release key, produce an unsigned artifact instead of silently using
            // the publicly reproducible debug identity.
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // Keep libmega.so uncompressed and page-aligned so that it maps directly from the APK.
        jniLibs { useLegacyPackaging = false }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    lint { abortOnError = true }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.animation.ExperimentalSharedTransitionApi",
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
            "kotlinx.coroutines.FlowPreview",
        )
    }
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
}

// The APK records HEAD as its source revision, so a release build needs a clean tree. A git
// error stops the build too, and untracked files count whatever the user's git settings are.
val checkReleaseTree = tasks.register("checkReleaseTree") {
    val status = providers.exec {
        commandLine("git", "status", "--porcelain", "--untracked-files=normal")
    }.standardOutput.asText
    doLast {
        if (status.get().isNotBlank()) throw GradleException("Commit all changes before a release build.")
    }
}
tasks.named { it == "preReleaseBuild" }.configureEach { dependsOn(checkReleaseTree) }

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("junit:junit:4.13.2")
    implementation(project(":sdk"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.navigation.suite)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.compose.material3.adaptive.layout)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.lifecycle.process)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui.compose)
    implementation(libs.coil.compose)
    implementation(libs.telephoto.zoomable.image.coil)
    implementation(libs.profileinstaller)
}
