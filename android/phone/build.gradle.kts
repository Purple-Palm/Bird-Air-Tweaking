plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** The repo's decoders/current.json, bundled as assets/decoder/current.json (the fallback when offline). */
abstract class DecoderAsset : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().asFile.resolve("decoder")
        dir.mkdirs()
        source.get().asFile.copyTo(dir.resolve("current.json"), overwrite = true)
    }
}

val decoderAsset = tasks.register<DecoderAsset>("decoderAsset") {
    source.set(rootProject.layout.projectDirectory.file("../decoders/current.json"))
}

android {
    namespace = "io.github.purplepalm.birdair.phone"
    compileSdk = 35
    defaultConfig {
        // phone and watch share one applicationId and one signing key: the Wear Data Layer needs both
        applicationId = "io.github.purplepalm.birdair"
        minSdk = 29
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
    testOptions { unitTests.all { it.systemProperty("repoRoot", rootDir.parentFile.absolutePath) } }
}

androidComponents {
    onVariants { v -> v.sources.assets?.addGeneratedSourceDirectory(decoderAsset, DecoderAsset::outputDir) }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("androidx.wear:wear-remote-interactions:1.0.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // play-services-base pulls fragment 1.1.0; registerForActivityResult needs 1.3+
    implementation("androidx.fragment:fragment:1.8.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}
