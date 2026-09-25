import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import groovy.json.JsonSlurper
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appConfig = JsonSlurper().parse(rootProject.file("app-config.json")) as Map<*, *>
val versionConfig = JsonSlurper().parse(rootProject.file("android-version.json")) as Map<*, *>
val sdkConfig = Properties().apply {
    rootProject.file("android-sdk.properties").inputStream().use { load(it) }
}

fun quoted(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val configuredApplicationId = appConfig["applicationId"] as String
val configuredAppName = appConfig["appName"] as String
val configuredManifestUrl = appConfig["dataManifestUrl"] as String

android {
    namespace = "com.actionanand.localtell.app" // source/R namespace; applicationId remains configurable
    compileSdk = sdkConfig.getProperty("COMPILE_SDK_VERSION").toInt()
    buildToolsVersion = sdkConfig.getProperty("BUILD_TOOLS_VERSION")

    defaultConfig {
        applicationId = configuredApplicationId
        minSdk = sdkConfig.getProperty("MIN_SDK_VERSION").toInt()
        targetSdk = sdkConfig.getProperty("TARGET_SDK_VERSION").toInt()
        versionCode = (versionConfig["versionCode"] as Number).toInt()
        versionName = versionConfig["versionName"] as String

        buildConfigField("String", "DATA_MANIFEST_URL", quoted(configuredManifestUrl))
        resValue("string", "app_name", configuredAppName)
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}


kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation("com.google.android.gms:play-services-location:21.3.0")

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation("junit:junit:4.13.2")
}
