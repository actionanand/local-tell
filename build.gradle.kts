plugins {
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}

// AGP 9 uses built-in Kotlin. Pin the Kotlin Gradle Plugin used by AGP so the
// Compose compiler plugin and Kotlin compiler stay on the same release line.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}
