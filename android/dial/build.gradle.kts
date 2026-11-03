plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
// The watch's dial faces (DialFace: STACK, TAKEOVER, GROW, RING, CLASSIC), their state and the drawing kit: Compose UI and
// foundation only, so the watch draws them and the phone shows the same faces as live previews.
android {
    namespace = "io.github.purplepalm.birdair.dial"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests.all { it.systemProperty("repoRoot", rootDir.parentFile.absolutePath) } }
}
dependencies {
    api(project(":core"))
    api(platform("androidx.compose:compose-bom:2024.12.01"))
    api("androidx.compose.ui:ui")
    api("androidx.compose.foundation:foundation")
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}
