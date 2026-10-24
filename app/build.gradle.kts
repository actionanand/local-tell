import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
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
val configuredHomeAnnouncementUrl = appConfig["homeAnnouncementUrl"] as String
val configuredManifestSources = appConfig["dataManifestSources"] as List<*>
val configuredTowerSurvey = appConfig["enableTowerSurvey"] as? Boolean ?: false
val configuredCloudBackup = appConfig["enableCloudBackup"].let { value ->
    require(value is Boolean) { "app-config.json: enableCloudBackup must be a JSON boolean (true or false, not a string)." }
    value
}
fun configuredLimit(key: String, maximum: Int): Int {
    val value = appConfig[key]
    require(value is Byte || value is Short || value is Int || value is Long) {
        "app-config.json: $key must be an integer between 1 and $maximum (not a decimal or string)."
    }
    val number = (value as Number).toLong()
    require(number in 1L..maximum.toLong()) { "app-config.json: $key must be between 1 and $maximum; found $number." }
    return number.toInt()
}
val configuredMaxSavedPlaces = configuredLimit("maxSavedPlaces", 200)
val configuredMaxJourneyPoints = configuredLimit("maxJourneyPoints", 1000)
val configuredDropboxAppKey = System.getenv("DROPBOX_APP_KEY")?.trim()
    ?.takeIf { it.matches(Regex("[A-Za-z0-9]+")) }
    ?: error("DROPBOX_APP_KEY must be set to the Dropbox App Key before building LocalTell.")

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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "HOME_ANNOUNCEMENT_URL", quoted(configuredHomeAnnouncementUrl))
        buildConfigField("String", "DATA_MANIFEST_SOURCES_JSON", quoted(JsonOutput.toJson(configuredManifestSources)))
        buildConfigField("Boolean", "ENABLE_TOWER_SURVEY", configuredTowerSurvey.toString())
        buildConfigField("boolean", "ENABLE_CLOUD_BACKUP", configuredCloudBackup.toString())
        buildConfigField("int", "MAX_SAVED_PLACES", configuredMaxSavedPlaces.toString())
        buildConfigField("int", "MAX_JOURNEY_POINTS", configuredMaxJourneyPoints.toString())
        // Public OAuth client identifier only. No Dropbox app secret is used by Android.
        buildConfigField("String", "DROPBOX_APP_KEY", quoted(configuredDropboxAppKey))
        resValue("string", "app_name", configuredAppName)
        manifestPlaceholders["dropboxAppKey"] = configuredDropboxAppKey
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
    implementation(libs.dropbox.core)
    implementation(libs.dropbox.android)
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
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation(libs.androidx.work.testing)
    // Android's org.json stubs cannot parse manifests in local JVM tests.
    testImplementation("org.json:json:20240303")
}
