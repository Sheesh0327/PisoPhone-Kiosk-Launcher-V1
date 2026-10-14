import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
}

// CI sets GITHUB_RUN_NUMBER (BUILD_NUMBER overrides it), which only ever increases; an installed phone refuses an update
// whose versionCode is not higher. Local builds use 1.
val appVersionCode = (System.getenv("BUILD_NUMBER") ?: System.getenv("GITHUB_RUN_NUMBER"))?.toIntOrNull() ?: 1

// The name people see is the build number cut into digits: hundreds.tens.ones (build 437 is 4.3.7, build 1000 is 10.0.0); only the
// build number (versionCode) decides what is newer.
val appVersionBase = "${appVersionCode / 100}.${appVersionCode / 10 % 10}.${appVersionCode % 10}"

// CI sets these for branch (dev) builds so the APK checks its own branch's /update folder instead
// of production. Local and main builds keep the production defaults.
val updateBaseUrl = System.getenv("UPDATE_BASE_URL")?.takeIf { it.isNotBlank() }
    ?: "https://pisophone.pages.dev/update"
val buildChannel = System.getenv("BUILD_CHANNEL")?.takeIf { it.isNotBlank() } ?: "stable"

// Room writes a JSON snapshot of each database version here so future migrations can be verified against the real
// previous schema. CI commits a new version's snapshot by itself and fails if a committed one changes (build-apk.yml).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.pisophone.kiosk"
    compileSdk { version = release(36) { minorApiLevel = 1 } }

    defaultConfig {
        applicationId = "com.pisophone.kiosk"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionName = if (buildChannel == "stable") appVersionBase else "$appVersionBase-dev"
        buildConfigField("String", "UPDATE_BASE_URL", "\"$updateBaseUrl\"")
        buildConfigField("String", "BUILD_CHANNEL", "\"$buildChannel\"")
        // The owner public key (the same file the router program embeds): the app checks recovery QR codes against it.
        val ownerKeyFile = rootProject.file("tools/pisoportal/owner_key.b64")
        val ownerPublicKey = if (ownerKeyFile.exists()) ownerKeyFile.readText().trim() else ""
        buildConfigField("String", "OWNER_PUBKEY_B64", "\"$ownerPublicKey\"")
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
            // Line coverage of the unit tests (CI: :app:createDebugUnitTestCoverageReport, scripts/check_coverage.py).
            enableUnitTestCoverage = true
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
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // Screenshot tests (src/test/.../screenshot): goldens live in src/test/screenshots; -Pscreenshots.update=true re-records them.
                // a failed test says why in the log (the message, not a page of stack)
                test.testLogging {
                    events("failed")
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
                    showStackTraces = false
                }
                test.systemProperty("screenshots.dir", "$projectDir/src/test/screenshots")
                test.systemProperty("screenshots.diff.dir", "${layout.buildDirectory.get().asFile}/screenshot-diffs")
                test.systemProperty("screenshots.update", providers.gradleProperty("screenshots.update").getOrElse("false"))
                // Robolectric tests load their classes through their own class loader; without this their coverage is not counted.
                test.extensions.findByType(JacocoTaskExtension::class.java)?.apply {
                    isIncludeNoLocationClasses = true
                    excludes = listOf("jdk.internal.*")
                }
            }
        }
    }
    // Android Lint (CI job "Android Lint" in build-apk.yml): findings that existed when it was switched on are in lint-baseline.xml, and
    // anything new fails the build. The baseline only ever shrinks.
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = false
        htmlReport = true
        xmlReport = true
    }
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
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    testImplementation(libs.androidx.core)
    testImplementation(libs.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    debugImplementation(libs.androidx.compose.ui.tooling)
    ksp(libs.androidx.room.compiler)
}

// In CI a release build must be signed with the production key, unless it is the unsigned build of the CI pipeline.
// Without this check a missing secret silently produces an unsigned or wrongly signed APK that installed phones reject.
gradle.taskGraph.whenReady {
    val buildsRelease = allTasks.any {
        it.path.startsWith(":app:") &&
            it.name.contains("Release") &&
            (it.name.startsWith("assemble") || it.name.startsWith("bundle"))
    }
    // build-apk.yml builds the release APK unsigned (UNSIGNED_BUILD=1) in a job with no secrets; a separate job signs it.
    if (buildsRelease && System.getenv("CI") == "true" && System.getenv("UNSIGNED_BUILD") != "1") {
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
