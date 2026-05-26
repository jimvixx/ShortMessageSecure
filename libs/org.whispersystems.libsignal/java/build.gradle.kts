// :java build.gradle.kts

plugins {
    `java-library`
}

group = "org.whispersystems"
version = "1.0.0"
base.archivesName.set("signal-protocol-java")

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8

    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    implementation("org.whispersystems:curve25519-java:0.5.0")
    api("com.google.protobuf:protobuf-java:4.35.0")

    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
