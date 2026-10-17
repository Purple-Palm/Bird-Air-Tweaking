pluginManagement {
    repositories {
        google()
        // Google's mirror of Maven Central first: Central rate-limits shared egress IPs (429)
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}
rootProject.name = "bird-air-lab"
include(":core", ":phone", ":wear")
