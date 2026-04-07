// :libs/org.whispersystems.libsignal build.gradle.kts
subprojects {
    extra["version_number"] = "2.7.1"
    extra["group_info"] = "org.whispersystems"
    extra["curve25519_version"] = "0.5.0"

    if (JavaVersion.current().isJava8Compatible) {
        allprojects {
            tasks.withType(Javadoc::class.java).configureEach {
                options.addStringOption("Xdoclint:none", "-quiet")
            }
        }
    }
}
