pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    plugins {
        id("com.android.application") version "9.1.0" apply false
        kotlin("android") version "2.3.20" apply false
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "ShortMessageSecure"

include(":app")
project(":app").projectDir = file("app")

include(":org.whispersystems.jobmanager")
project(":org.whispersystems.jobmanager").projectDir = file("libs/org.whispersystems.jobmanager")

include(":org.whispersystems.libsignal.java")
project(":org.whispersystems.libsignal.java").projectDir = file("libs/org.whispersystems.libsignal/java")

include(":org.whispersystems.libsignal.tests")
project(":org.whispersystems.libsignal.tests").projectDir = file("libs/org.whispersystems.libsignal/tests")
