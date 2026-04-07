// :libs/org.whispersystems.libsignal.tests build.gradle.kts

plugins {
    java
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(project(":org.whispersystems.libsignal.java"))
}
tasks.test {
    useJUnit()

    // VRF is NYI in curve25519-java => this test can never pass on JVM
    filter {
        excludeTestsMatching("org.whispersystems.libsignal.devices.DeviceConsistencyTest")
    }
}
