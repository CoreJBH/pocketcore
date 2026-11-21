plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jbitmstr.pocketcore"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jbitmstr.pocketcore"
        minSdk = 26
        targetSdk = 35
        // versionCode 8: the launch-screen halo is no longer clipped. The glow
        // is drawn by the full-window layer as one radial gradient that fades
        // to transparent 150dp out, instead of inside the tile's 120dp box,
        // whose layer (the fade's alpha) cut it off in a straight line above and
        // below the tile. The endless size pulse is gone too: the mark settles
        // once from 95% and stays still until the overlay fades out.
        // versionCode 7: launch screen enlarged - the splash tile draws the
        // tesseract at 73% instead of 48.6% and the 96dp box became 120dp, so
        // the mark reads at 88dp on a 360dp phone instead of 47dp. Launcher
        // icons unchanged; docs/hero-splash.png is the README signboard.
        // versionCode 6: launcher, themed-icon, splash and notification art
        // redrawn from pocketcore-icon-tesseract2.png - the isometric tesseract
        // with a glowing core, nothing else inside. The adaptive foreground is
        // now a real alpha layer over the #030B27 field.
        // versionCode 5: notification card rebuilt to the node-app layout of the
        // reference (Mandacaru): single small icon on the left (no large icon),
        // state in the header row, app-style timestamp, ocean blue when caught up,
        // cobalt with a live progress bar while syncing, and header pre-sync
        // progress read from debug.log instead of RPC's 0.00%.
        // versionCode 4: RPC port collision fix (orphan node, busy port, free-port
        // fallback) plus the EXPLORE tab, offline peer country flags, raw
        // transaction broadcast and watch-only descriptor scans.
        // versionCode 3: launch crash fix (splash used the adaptive-icon XML as a
        // bitmap) and launcher art rebuilt inside the 66dp adaptive-icon safe zone.
        // versionCode 2: edge-to-edge insets fix, launch splash, colorized
        // status card, midnight-blue theme and the blue launcher icon.
        versionCode = 8
        versionName = "1.0.0"

        ndk {
            // The bundled bitcoind is a single aarch64-linux-android executable.
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // Keep the bundled bitcoind as a real file that is extracted into
        // nativeLibraryDir at install time, so it can be executed.
        jniLibs {
            useLegacyPackaging = true
            // The daemon is already stripped and must be shipped byte for byte:
            // never let the packaging step rewrite the executable.
            keepDebugSymbols += "**/libbitcoind.so"
        }
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/DEPENDENCIES"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
