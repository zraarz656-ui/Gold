// Root build file. Configuration for the pure Kotlin/JVM `engine` module lives in
// engine/build.gradle.kts. A gradle.properties file pins the JDK toolchain so the
// build works without an Android SDK or network-flaky toolchain auto-provisioning.
plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("android") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.android.application") version "8.4.2" apply false
    id("com.android.library") version "8.4.2" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    id("com.google.dagger.hilt.android") version "2.51.1" apply false
}

allprojects {
    group = "com.tradequest"
    version = "0.1.0"
}
