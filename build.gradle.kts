// Root build.gradle.kts

plugins {
    id("com.android.application") version "9.1.1" apply false
    kotlin("android") version "2.3.20" apply false
}
subprojects {
    extra["version_number"] = "2.4.0"
    extra["group_info"] = "org.whispersystems"
    extra["curve25519_version"] = "0.5.0"

        configurations.all {
        exclude(group = "com.android.support", module = "appcompat-v7")
        exclude(group = "com.android.support", module = "support-v4")
        exclude(group = "com.android.support", module = "support-compat")
        exclude(group = "com.android.support", module = "design")
    }
}
