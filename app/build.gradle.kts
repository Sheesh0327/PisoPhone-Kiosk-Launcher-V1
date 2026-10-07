plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
}

// CI sets BUILD_NUMBER (CircleCI: 400 + the pipeline number; GITHUB_RUN_NUMBER on the old GitHub Actions builds), which only
// ever increases; an installed phone refuses an update whose
// versionCode is not higher. Local builds use 1.
val appVersionCode = (System.getenv("BUILD_NUMBER") ?: System.getenv("GITHUB_RUN_NUMBER"))?.toIntOrNull() ?: 1

// CI sets these for branch (dev) builds so the APK checks its own branch's /update folder instead
// of production. Local and main builds keep the production defaults.
val updateBaseUrl = System.getenv("UPDATE_BASE_URL")?.takeIf { it.isNotBlank() }
    ?: "https://pisophone.pages.dev/update"
val buildChannel = System.getenv("BUILD_CHANNEL")?.takeIf { it.isNotBlank() } ?: "stable"

android {
    namespace = "com.pisophone.kiosk"
    compileSdk { version = release(36) { minorApiLevel = 1 } }

    defaultConfig {
        applicationId = "com.pisophone.kiosk"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = if (buildChannel == "stable") "1.0.$appVersionCode" else "1.0.$appVersionCode-dev"
        buildConfigField("String", "UPDATE_BASE_URL", "\"$updateBaseUrl\"")
        buildConfigField("String", "BUILD_CHANNEL", "\"$buildChannel\"")
    }

    signingConfigs {
        if (file("$rootDir/debug.keystore").exists()) {
            create("debugConfig") {
                storeFile = file("$rootDir/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        val keystorePath = System.getenv("KEYSTORE_PATH")?.trim()
        if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("STORE_PASSWORD")?.trim()
                keyAlias = System.getenv("KEY_ALIAS")?.trim()
                keyPassword = System.getenv("KEY_PASSWORD")?.trim()
            }
        }
    }

    buildTypes {
        release {
            isCrunchPngs = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            if (signingConfigs.findByName("debugConfig") != null) {
                signingConfig = signingConfigs.getByName("debugConfig")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests { isIncludeAndroidResources = true } }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    testImplementation(libs.androidx.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    debugImplementation(libs.androidx.compose.ui.tooling)
    ksp(libs.androidx.room.compiler)
}

// In CI a release build must be signed with the production key. Without this check a missing
// secret silently produces an unsigned or wrongly signed APK that installed phones reject.
gradle.taskGraph.whenReady {
    val buildsRelease = allTasks.any {
        it.path.startsWith(":app:") &&
            it.name.contains("Release") &&
            (it.name.startsWith("assemble") || it.name.startsWith("bundle"))
    }
    if (buildsRelease && System.getenv("CI") == "true") {
        val missing = listOf("KEYSTORE_PATH", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
            .filter { System.getenv(it).isNullOrBlank() }
        if (missing.isNotEmpty() || android.signingConfigs.findByName("release") == null) {
            throw GradleException(
                "Release signing is not configured (missing: ${missing.joinToString().ifEmpty { "keystore file" }}). " +
                    "Refusing to build an unsigned release APK in CI.",
            )
        }
    }
}
