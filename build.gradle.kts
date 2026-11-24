plugins {
    // AGP 8.11 is the plugin that builds against API 36 (compileSdk and targetSdk
    // 36): 8.7 was tested up to API 35 and warns on anything newer.
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
