plugins {
    id("com.android.application")
}

// ============================================================
// APP SETTINGS - edit this block when starting a new app
// ============================================================
val appName = "Soulection Radio"             // name shown under the icon
val appId = "com.hotwheelz3000.soulection"   // must be unique per app
val startUrl = "https://radio.soulection.com/" // site the app opens
val appVersion = "1.0"                       // bump for a new version
// ============================================================

// Build number comes from GitHub Actions (run number); 1 for local builds
val buildNumber = (System.getenv("BUILD_NUMBER") ?: "1").toInt()

// In-app update check reads update.json from this repo's latest release.
// Filled in automatically on GitHub Actions; empty (off) for local builds.
val repo = System.getenv("GITHUB_REPOSITORY") ?: ""
val updateUrl = if (repo.isNotEmpty())
    "https://github.com/$repo/releases/latest/download/update.json" else ""

android {
    namespace = "com.hotwheelz3000.soulection"
    compileSdk = 37

    defaultConfig {
        applicationId = appId
        minSdk = 26
        targetSdk = 37
        versionCode = buildNumber
        versionName = appVersion

        resValue("string", "app_name", appName)
        buildConfigField("String", "START_URL", "\"$startUrl\"")
        buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
    }

    // Same key every build, so new APKs install over old ones.
    // For sideloading only - not for the Play Store.
    signingConfigs {
        create("sideload") {
            storeFile = rootProject.file("keystore/sideload.jks")
            storePassword = "sideload"
            keyAlias = "sideload"
            keyPassword = "sideload"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.webkit:webkit:1.18.0")
    implementation("androidx.media:media:1.8.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
}
