plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
// -Pshots: also render the screens to PNG with Robolectric (src/shots, see Shots.kt)
val shots = providers.gradleProperty("shots").isPresent
android {
    namespace = "io.github.purplepalm.birdair.wear"
    compileSdk = 35
    defaultConfig {
        // phone and watch share one applicationId and one signing key: the Wear Data Layer needs both
        applicationId = "io.github.purplepalm.birdair"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes { getByName("debug") { signingConfig = signingConfigs.getByName("debug") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    if (shots) {
        sourceSets.getByName("test").kotlin.srcDir("src/shots/kotlin")
        testOptions { unitTests { isIncludeAndroidResources = true } }
    }
}
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    // play-services pulls fragment 1.2.4; the Activity Result API needs ≥ 1.3
    implementation("androidx.fragment:fragment:1.8.5")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.wear:wear:1.3.0")
    implementation("androidx.wear:wear-ongoing:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    if (shots) {
        testImplementation("org.robolectric:robolectric:4.14.1")
        testImplementation("androidx.compose.ui:ui-test-junit4")
        debugImplementation("androidx.compose.ui:ui-test-manifest")
    }
}
