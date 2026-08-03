pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("com.gradle.develocity") version "4.5.0"
    id("com.gradle.common-custom-user-data-gradle-plugin") version "2.8.0"
}

apply(from = "gradle/develocity.settings.gradle")

rootProject.name = "takpacket-sdk"
