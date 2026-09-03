// :app build.gradle.kts

import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasKeystore = keystorePropertiesFile.exists()

if (hasKeystore) {
    FileInputStream(keystorePropertiesFile).use { stream ->
        keystoreProperties.load(stream)
    }
}

val changelogFile = rootProject.file("CHANGELOG.md")
val playChangelogFile = providers.provider {
    val versionCode = requireNotNull(android.defaultConfig.versionCode) {
        "Missing versionCode in android.defaultConfig"
    }
    rootProject.file("fastlane/metadata/android/en-US/changelogs/$versionCode.txt")
}

tasks.register("generatePlayChangelog") {
    group = "release"
    description = "Generate Google Play and F-Droid release notes from CHANGELOG.md"

    inputs.file(changelogFile)
    outputs.file(playChangelogFile)
    outputs.upToDateWhen { false }

    doLast {
        require(changelogFile.isFile) { "Missing $changelogFile" }
        val versionName = requireNotNull(android.defaultConfig.versionName) {
            "Missing versionName in android.defaultConfig"
        }
        val outputFile = playChangelogFile.get()

        val lines = changelogFile.readLines()
        val firstReleaseHeadingIndex = lines.indexOfFirst { line ->
            line.startsWith("## ")
        }
        require(firstReleaseHeadingIndex >= 0) {
            "No release section found in $changelogFile"
        }

        val expectedHeading = "## v$versionName"
        val actualHeading = lines[firstReleaseHeadingIndex].trim()
        require(actualHeading == expectedHeading) {
            "Latest changelog section must be '$expectedHeading', got '$actualHeading'"
        }

        val notes = lines
            .drop(firstReleaseHeadingIndex + 1)
            .takeWhile { line -> !line.startsWith("## ") }
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("- ")) {
                    "* ${trimmed.removePrefix("- ")}"
                } else {
                    null
                }
            }
            .joinToString("\n")

        require(notes.isNotEmpty()) {
            "No list entries found under '$expectedHeading'"
        }

        val characterCount = notes.codePointCount(0, notes.length)
        require(characterCount <= 500) {
            "Play changelog is too long: $characterCount/500 characters"
        }

        outputFile.parentFile.mkdirs()
        outputFile.writeText("$notes\n")

        val relativePath = outputFile
            .relativeTo(rootProject.projectDir)
            .invariantSeparatorsPath
        println("GENERATED_PLAY_CHANGELOG=$relativePath")
    }
}

android {
    namespace = "org.jimvixx.smsecure"
    compileSdk = 36

    useLibrary("org.apache.http.legacy")

    defaultConfig {
        applicationId = "org.jimvixx.smsecure"
        versionCode = 10003
        versionName = "1.0.3"

        buildConfigField(
            "String",
            "ISSUES_REQUESTS_URL",
            "\"https://github.com/jimvixx/ShortMessageSecure/issues\""
        )
        buildConfigField(
            "String",
            "SOURCE_CODE_URL",
            "\"https://github.com/jimvixx/ShortMessageSecure\""
        )
        buildConfigField(
            "String",
            "MORE_DETAILS_URL",
            "\"https://github.com/jimvixx/ShortMessageSecure/blob/main/README.md\""
        )
        buildConfigField(
            "String",
            "PRIVACY_POLICY_URL",
            "\"https://github.com/jimvixx/ShortMessageSecure/blob/main/PRIVACY_POLICY.md\""
        )
        buildConfigField(
            "String",
            "INVITE_URL",
            "\"https://github.com/jimvixx/ShortMessageSecure\""
        )

        targetSdk = 36
        minSdk = 24

        vectorDrawables.useSupportLibrary = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        dataBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                val storeFilePath = keystoreProperties.getProperty("storeFile")
                val storePasswordValue = keystoreProperties.getProperty("storePassword")
                val keyAliasValue = keystoreProperties.getProperty("keyAlias")
                val keyPasswordValue = keystoreProperties.getProperty("keyPassword")

                if (storeFilePath.isNullOrBlank() ||
                    storePasswordValue.isNullOrBlank() ||
                    keyAliasValue.isNullOrBlank() ||
                    keyPasswordValue.isNullOrBlank()
                ) {
                    throw GradleException("Release signing is not configured correctly in keystore.properties")
                }

                storeFile = rootProject.file(storeFilePath)
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    packaging {
        resources.excludes.addAll(
            listOf(
                "LICENSE.txt",
                "LICENSE",
                "NOTICE",
                "asm-license.txt",
                "META-INF/LICENSE",
                "META-INF/NOTICE"
            )
        )
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                file("proguard-rules.pro")
            )

            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    lint {
        disable += "MissingTranslation"
        abortOnError = true
    }
}

dependencies {
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("org.assertj:assertj-core:3.27.7")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.mockito:mockito-core:5.23.0")

    annotationProcessor("com.github.bumptech.glide:compiler:5.0.7")
    annotationProcessor("com.squareup.dagger:dagger-compiler:1.2.5")

    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.fragment:fragment:1.9.0")
    implementation("androidx.gridlayout:gridlayout:1.1.0")
    implementation("androidx.legacy:legacy-support-v13:1.0.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.preference:preference:1.2.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    implementation("com.annimon:stream:1.2.2")

    implementation("com.davemorrissey.labs:subsampling-scale-image-view:3.10.0")

    implementation("com.fasterxml.jackson.core:jackson-annotations:2.22")
    implementation("com.fasterxml.jackson.core:jackson-core:2.22.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.2")

    implementation("com.github.bumptech.glide:glide:5.0.7")
    implementation("com.github.bumptech.glide:okhttp3-integration:5.0.7@aar")
    implementation("com.github.chrisbanes.photoview:library:1.2.4")
    implementation("com.github.guardianproject:TrustedIntents:0.2")

    implementation("com.google.android.material:material:1.14.0")
    implementation("com.google.protobuf:protobuf-java:4.35.1")
    implementation("com.google.zxing:core:3.5.4")

    implementation("com.googlecode.libphonenumber:libphonenumber:9.0.37")

    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    implementation("com.jpardogo.materialtabstrip:library:1.1.1")

    implementation("com.klinkerapps:android-smsmms:5.2.6")

    implementation("com.makeramen:roundedimageview:2.3.0")

    implementation("com.melnykov:floatingactionbutton:1.3.0")

    implementation("com.squareup.dagger:dagger:1.2.5")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")

    implementation("com.takisoft.preferencex:preferencex-colorpicker:1.1.0")
    implementation("com.takisoft.preferencex:preferencex:1.1.0")

    implementation("org.apache.httpcomponents:httpclient-android:4.3.5.1")

    implementation("org.greenrobot:eventbus:3.3.1")

    implementation("pl.tajchert:waitingdots:0.2.0")

    implementation("se.emilsjolander:stickylistheaders:2.7.0")

    implementation(project(":org.whispersystems.libsignal.java"))
    implementation(project(":org.whispersystems.jobmanager"))
}
