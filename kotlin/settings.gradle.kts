pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

apply(from = "gradle/build-cache.settings.gradle")

rootProject.name = "takpacket-sdk"
