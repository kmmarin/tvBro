// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Version: one place, overridable from CI with -Ptvbro.version=1.2.3 (the release tag without the "v").
// versionCode = major * 10000 + minor * 100 + patch, so 0.1.0 -> 100, 1.2.3 -> 10203 (minor and patch stay below 100).
val tvbroVersion = (findProperty("tvbro.version") as String?)?.removePrefix("v") ?: "0.1.0"
fun versionCodeOf(v: String): Int {
    val p = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
    require(p[1] < 100 && p[2] < 100) { "minor and patch must be below 100: $v" }
    return p[0] * 10000 + p[1] * 100 + p[2]
}

// Release signing: from environment variables (CI secrets) or a gitignored keystore.properties next to
// settings.gradle.kts (see keystore.properties.example). Without either, the release APK is left unsigned.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(key)?.takeIf { it.isNotBlank() }

// In-app update check against GitHub Releases. -Ptvbro.updateCheck=false builds it out (F-Droid builds).
val updateRepo = (findProperty("tvbro.updateRepo") as String?) ?: "kmmarin/tvBro"
val updateCheck = ((findProperty("tvbro.updateCheck") as String?) ?: "true").toBoolean()

android {
    namespace = "io.github.kmmarin.tvbro"
    compileSdk = 34
    defaultConfig {
        applicationId = "io.github.kmmarin.tvbro"
        minSdk = 24 // Android 7.0; Fire TV Stick (Fire OS 6 = Android 7.1, armv7) included
        targetSdk = 34
        versionCode = versionCodeOf(tvbroVersion)
        versionName = tvbroVersion
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        buildConfigField("boolean", "UPDATE_CHECK", updateCheck.toString())
    }
    signingConfigs {
        val store = signingValue("storeFile", "TVBRO_KEYSTORE")
        if (store != null) {
            create("release") {
                storeFile = rootProject.file(store)
                storePassword = signingValue("storePassword", "TVBRO_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "TVBRO_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "TVBRO_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true // R8
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") // null: unsigned APK
            vcsInfo { include = false } // no git commit file inside the APK (reproducible builds)
        }
    }
    // No Google-encrypted dependency metadata blob in the APK (F-Droid / reproducible builds).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1,INDEX.LIST,DEPENDENCIES}" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":shared"))
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10") // SMB/CIFS network share for recordings (LGPL-2.1, unmodified library)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")

    constraints {
        // jcifs-ng 2.1.10 pulls bcprov 1.76 (CVE-2024-29857, CVE-2024-30171, CVE-2024-30172, CVE-2025-8916)
        implementation("org.bouncycastle:bcprov-jdk18on:1.80") { because("security fixes over the 1.76 that jcifs-ng asks for") }
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
