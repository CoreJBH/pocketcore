import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing is read from keystore/keystore.properties (never committed)
// or, for a CI checkout, from the environment. It is deliberately strict: a
// release artifact that cannot be signed fails the build instead of being
// written unsigned, and nothing falls back to the debug key - an APK signed
// with that key would install over nobody's app while looking like a release.
// Code checks (lint, unit tests) still run without any signing material.
val keystorePropertiesFile = rootProject.file("keystore/keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun signingValue(propertyKey: String, environmentKey: String): String? =
    keystoreProperties.getProperty(propertyKey)?.takeIf { it.isNotBlank() }
        ?: System.getenv(environmentKey)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "POCKETCORE_KEYSTORE_FILE")
val releaseStorePassword = signingValue("storePassword", "POCKETCORE_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "POCKETCORE_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "POCKETCORE_KEY_PASSWORD")

// A storeFile that is named but not on disk is a broken configuration, not a
// reason to continue without one.
val releaseKeystorePresent = releaseStoreFile?.let { rootProject.file(it).isFile } == true
val missingReleaseSigning = buildList {
    if (releaseStoreFile == null) add("storeFile (POCKETCORE_KEYSTORE_FILE)")
    if (releaseStorePassword == null) add("storePassword (POCKETCORE_KEYSTORE_PASSWORD)")
    if (releaseKeyAlias == null) add("keyAlias (POCKETCORE_KEY_ALIAS)")
    if (releaseKeyPassword == null) add("keyPassword (POCKETCORE_KEY_PASSWORD)")
    val allValuesGiven = releaseStoreFile != null && releaseStorePassword != null &&
        releaseKeyAlias != null && releaseKeyPassword != null
    if (allValuesGiven && !releaseKeystorePresent) {
        add("the keystore file at $releaseStoreFile")
    }
}
val hasReleaseKeystore = missingReleaseSigning.isEmpty()

// Tasks that write a release artifact, and therefore need the signature. Lint
// and unit tests are left out on purpose: they check code and sign nothing.
fun taskNeedsReleaseSignature(request: String): Boolean {
    val task = request.substringAfterLast(':').lowercase()
    val codeCheck = task.startsWith("lint") || task.startsWith("test") ||
        task.startsWith("connected") || task.startsWith("dependencies") ||
        task.startsWith("dependency") || task == "tasks" || task == "help"
    if (codeCheck) return false
    return task.contains("release") || task in setOf(
        "assemble", "bundle", "build", "install", "publish", "package", "sign"
    )
}

if (!hasReleaseKeystore && gradle.startParameter.taskNames.any { taskNeedsReleaseSignature(it) }) {
    throw GradleException(
        "Release signing is not configured (missing: ${missingReleaseSigning.joinToString(", ")}). " +
            "Put storeFile, storePassword, keyAlias and keyPassword in " +
            "keystore/keystore.properties, or set POCKETCORE_KEYSTORE_FILE, " +
            "POCKETCORE_KEYSTORE_PASSWORD, POCKETCORE_KEY_ALIAS and POCKETCORE_KEY_PASSWORD. " +
            "A release build is not produced unsigned, and never with the debug key."
    )
}

// The bundled daemon's SHA-256, taken from the file this APK ships.
//
// The app executes the extracted copy from its native library directory, so the
// hash of what was built has to travel with the build: BitcoindNode hashes the
// extracted file against this value before it runs it (versionCode 12). The
// value is read here, from the same bytes the packaging step packs, so it cannot
// disagree with the APK; a build without the daemon fails instead of producing
// an app that refuses to start.
val bitcoindBinary = file("src/main/jniLibs/arm64-v8a/libbitcoind.so")
val bitcoindSha256: String = run {
    require(bitcoindBinary.isFile) {
        "Missing bundled daemon at ${bitcoindBinary.absolutePath}. " +
            "Run scripts/build-bitcoind-android.sh or restore app/src/main/jniLibs/arm64-v8a/libbitcoind.so."
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(bitcoindBinary.readBytes())
    digest.joinToString("") { byte -> "%02x".format(byte) }
}

android {
    namespace = "com.corejbh.pocketcore"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.corejbh.pocketcore"
        minSdk = 26
        targetSdk = 35
        // versionCode 17: seventh review. debug.log can shrink under the offset
        // a start recorded (-shrinkdebugfile keeps only the recent tail), so the
        // bind evidence is read from the start of the truncated file instead of
        // from a position past its end - otherwise an empty window called a
        // healthy node FOREIGN and killed it. The snapshot operations (import,
        // export, load, clear) run one at a time behind a gate and write the
        // state under the node mutex, so a clear cannot delete the file an import
        // is publishing while an export dumps the chainstate a load is changing.
        // RPC poll passes no longer overlap. Explorer amounts and fees are exact
        // integer satoshi arithmetic instead of Double sums.
        // versionCode 16: sixth security and state review. The UTXO snapshot
        // export and load go through the same client() gate as every other RPC
        // caller, so a dump is never sent to a port this app has not proved is
        // its own node; the log reads are bounded (debug.log past the read
        // offset is read in at most 1 MiB, the daemon's console tail through the
        // 256 KB window) so a log that grew to hundreds of megabytes can no
        // longer throw OutOfMemoryError; an orphan daemon left by a killed app
        // process is ended with the stop RPC from its own cookie or stored
        // credentials and waited on, instead of being killed outright, which
        // can leave the chainstate needing a reindex; ABORT clears a scan state
        // even when the node is gone; START NOW's bypass is dropped once the
        // Wi-Fi / charger condition is actually met; and a temporary RPC
        // timeout no longer walks a RUNNING node back to STARTING.
        // versionCode 15: fifth security review. On Android 10+ SELinux keeps
        // the app from reading /proc/<pid>/net/tcp, so the owner of the RPC
        // port could not be established and a node that started fine never
        // left STARTING: when the socket tables cannot be read, the owner is
        // now established from three facts instead - the daemon is alive, the
        // RPC port answers on loopback, and the daemon's own debug.log, read
        // from the byte offset this start began at, shows Core binding the RPC
        // listener (FOREIGN and UNKNOWN are still refusals). On Android 10-12 a
        // background app cannot read the clipboard either, so the one-minute
        // clear of a sensitive copy now runs without the "still our copy"
        // comparison when the clipboard cannot be read. Turning Tor on first
        // probes Orbot's listener on 127.0.0.1:9050 and says so before enabling;
        // the settings text documents Core's onlynet=onion. Editing the settings
        // no longer turns a STOPPED node into WAITING, zpub/ypub/vpub/upub keys
        // are rebased to the xpub/tpub form Core's descriptors accept before
        // they reach scantxoutset, and switching to Full (prune=0) rebuilds the
        // selected network's chain data only, leaving the other networks' chains
        // in place.
        // versionCode 14: fourth security review. No RPC request is built before
        // this start's listening socket is proved to be the app's own daemon
        // (client() needs RUNNING, a verified listener and a live process, and
        // EXPLORE's lookups and broadcast are only offered while RUNNING); a
        // reply from a poll pass that began before a stop or a new start is
        // dropped instead of turning a stopped node back into a running one; the
        // Keystore key is shared by every secret and is replaced only when the
        // Keystore reports the key itself gone, so one entry with a bad GCM tag
        // no longer takes the RPC password and the descriptor with it (the store
        // and the settings store are locked too); and Tor routing now also
        // writes listen=0 and dnsseed=0, so the node neither binds a clearnet
        // inbound socket nor resolves the DNS seeds in the clear.
        // versionCode 13: third security review. The RPC credentials are handed
        // only to a listener proved to be this daemon (an unreadable /proc entry
        // is a refusal now, not a guess), the watch-only descriptor is encrypted
        // at rest like the RPC password, a sensitive copy on Android 12 and below
        // is cleared from their clipboard after a minute, a failed HTTP exchange
        // no longer shares RPC_MISC_ERROR's code (and -28 keeps the node in its
        // warm-up state), a block's transactions are no longer labelled MEMPOOL,
        // the storage card measures the selected network only, and a release that
        // cannot be signed fails the build instead of shipping unsigned.
        // versionCode 12: second security and code review. The daemon is hashed
        // against a checksum recorded at build time before it is executed, the
        // build script verifies Bitcoin Core's SHA256SUMS.asc signature, the
        // node's data and settings are excluded from cloud backup and
        // device-to-device transfer, watch-only descriptors and the debug log
        // are copied as sensitive clipboard items, an orphan daemon is only
        // killed when its graceful shutdown failed, the RPC credentials are
        // only sent to a socket the daemon itself holds, and an optional Orbot
        // SOCKS5 proxy can carry the peer traffic. Release signing no longer
        // includes the v1 (JAR) signature, and lint errors now fail the build.
        // versionCode 11: security and stability review. The RPC password is no
        // longer ever written in the clear - a Keystore that cannot encrypt keeps
        // it in memory only - and a key invalidated by a lock screen change is
        // replaced instead of failing every read. The RPC credential pair is
        // regenerated on every start and both endpoints are held from the free
        // port check until the daemon is launched, so a loopback port that
        // changes hands in between never sees a usable password. Coroutine
        // cancellation is no longer swallowed by the scan, explorer and poll
        // loops, the process scan matches this app's UID before reporting a PID,
        // an unresponsive `-version` probe is killed, the foreground notification
        // is private on the lock screen, and release builds are minified.
        // versionCode 10: first Google Play build. The applicationId moved from
        // com.jbitmstr.pocketcore to com.corejbh.pocketcore and the build is now
        // signed with a release keystore instead of the debug key, so it installs
        // as a separate app from the earlier debug APKs.
        // versionCode 9: security and stability review. An RPC port held by
        // another app no longer receives the app's credentials (a stop request
        // is only sent to a daemon of this app, found through /proc), the RPC
        // password is stored with a Keystore key instead of in plain
        // SharedPreferences and bitcoin.conf now carries rpcauth instead of the
        // password, sensitive copies are flagged for Android 13, WIF keys inside
        // descriptor functions are refused, and the policy/wake-lock/
        // explorer/tables paths were tightened.
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
        versionCode = 17
        versionName = "1.0.0"

        // The hash of the bundled daemon, embedded for the integrity check in
        // BitcoindNode.prepareBinary().
        buildConfigField("String", "BITCOIND_SHA256", "\"$bitcoindSha256\"")

        ndk {
            // The bundled bitcoind is a single aarch64-linux-android executable.
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        // Created only when the four values are there; the check above has
        // already failed a release build that has none, so this config is never
        // quietly missing for an artifact that ships.
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // v1 (JAR) signing is off: minSdk 26 devices verify APK Signature
                // Scheme v2, and an extra JAR signature only leaves a second,
                // weaker digest over the same files to check.
                enableV1Signing = false
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // No fallback: an unsigned release is not produced (see the check at
            // the top of this file).
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Shrinking and obfuscation for the build that is published: the
            // app uses no reflection, so no class or member has to be kept by
            // name (see proguard-rules.pro).
            isMinifyEnabled = true
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
        // A lint error is a defect in the build that is about to ship: it fails
        // the build instead of scrolling past.
        abortOnError = true
        // Lint runs as part of a release build too, so a release that is about to
        // be published cannot carry a fresh set of errors silently.
        checkReleaseBuilds = true
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
