// Root build file. Configuration for the pure Kotlin/JVM `engine` module lives in
// engine/build.gradle.kts. A gradle.properties file pins the JDK toolchain so the
// build works without an Android SDK or network-flaky toolchain auto-provisioning.
plugins {
    kotlin("jvm") version "2.0.21" apply false
}

allprojects {
    group = "com.tradequest"
    version = "0.1.0"
}
