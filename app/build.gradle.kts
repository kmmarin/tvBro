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
val tvbroVersion = (findProperty("tvbro.version") as String?)?.removePrefix("v") ?: "0.3.3"
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

// ---- Rust core (core/, docs/RUST_CORE.md): libtvbro_ffi.so for three ABIs + the generated UniFFI Kotlin ----
// Which implementation the switched call sites use by default: -Ptvbro.core=default (each row's compiled default,
// CoreSwitch.CORE_BY_DEFAULT), -Ptvbro.core=rust (all rows on the core) or -Ptvbro.core=kotlin (all rows on Kotlin,
// the fallback). Debug builds can override per row (Settings > Developer, debug.tvbro.core).
val coreDefault = ((findProperty("tvbro.core") as String?) ?: "default").lowercase()
require(coreDefault in setOf("default", "kotlin", "rust")) { "tvbro.core must be default, kotlin or rust: $coreDefault" }
val coreDir = rootProject.file("core")
val coreNdk = "27.3.13750724"
val coreOut = layout.buildDirectory.dir("generated/core")
// Android ABI -> Rust target, NDK clang prefix, extra link flags (16 KB pages on 64-bit, needed from Android 15 on)
val coreAbis = listOf(
    Triple("arm64-v8a", "aarch64-linux-android", "aarch64-linux-android"),
    Triple("armeabi-v7a", "armv7-linux-androideabi", "armv7a-linux-androideabi"),
    Triple("x86_64", "x86_64-linux-android", "x86_64-linux-android"),
)
val isWindows = System.getProperty("os.name").lowercase().contains("windows")
// cargo: -Ptvbro.cargo, $CARGO, $CARGO_HOME/bin, else from PATH
val cargoExe: String = (findProperty("tvbro.cargo") as String?)
    ?: System.getenv("CARGO")
    ?: System.getenv("CARGO_HOME")?.let { File(it, "bin/cargo" + if (isWindows) ".exe" else "") }?.takeIf { it.exists() }?.path
    ?: "cargo"
val coreSources = fileTree(coreDir) { include("Cargo.toml", "Cargo.lock", "tvbro-core/**", "tvbro-ffi/**"); exclude("**/target/**", "tvbro-ffi/jvm-smoke/**") }

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
        buildConfigField("String", "CORE_DEFAULT", "\"$coreDefault\"")
        // the Rust core ships for these ABIs only (JNA's own x86 / other libraries are dropped with them); an x86
        // TV emulator runs the armeabi-v7a build through its ARM translation
        ndk { abiFilters += coreAbis.map { it.first } }
    }
    ndkVersion = coreNdk
    // One APK per ABI (each carries one libtvbro_ffi.so instead of three) plus the universal APK, which stays the
    // main download (tvbro.apk) and what older updaters fetch. Only for release builds: debug / test builds keep the
    // single app-debug.apk the test tools install. -Ptvbro.abiSplits=false turns the splits off.
    val abiSplits = ((findProperty("tvbro.abiSplits") as String?) ?: "true").toBoolean() &&
        gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) || it.substringAfterLast(':') in setOf("assemble", "build") }
    splits {
        abi {
            isEnable = abiSplits
            reset()
            include(*coreAbis.map { it.first }.toTypedArray())
            isUniversalApk = true
        }
    }
    sourceSets["main"].java.srcDir(coreOut.map { it.dir("kotlin") })
    sourceSets["main"].jniLibs.srcDir(coreOut.map { it.dir("jniLibs") })
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
    testOptions {
        unitTests.isReturnDefaultValues = true
        // the comparison tests (CoreParityTest) load the host build of the core through JNA
        unitTests.all {
            it.dependsOn("coreHost")
            it.systemProperty("jna.library.path", File(coreDir, "target/debug").path)
            it.systemProperty("tvbro.root", rootProject.projectDir.path)
        }
    }
}

// cargo with the NDK's clang as linker and C compiler (bundled SQLite) for each Android target; no machine paths
// in the repository: the NDK comes from the Android SDK this build uses.
fun cargoEnv(): Map<String, String> {
    val ndk = android.ndkDirectory
    val host = when { isWindows -> "windows-x86_64"; System.getProperty("os.name").lowercase().contains("mac") -> "darwin-x86_64"; else -> "linux-x86_64" }
    val bin = File(ndk, "toolchains/llvm/prebuilt/$host/bin")
    val env = HashMap<String, String>()
    for ((abi, target, clang) in coreAbis) {
        val cc = File(bin, "${clang}24-clang" + if (isWindows) ".cmd" else "").path
        val t = target.uppercase().replace('-', '_')
        env["CARGO_TARGET_${t}_LINKER"] = cc
        env["CC_${target.replace('-', '_')}"] = cc
        env["AR_${target.replace('-', '_')}"] = File(bin, "llvm-ar" + if (isWindows) ".exe" else "").path
        if (abi != "armeabi-v7a") env["CARGO_TARGET_${t}_RUSTFLAGS"] = "-C link-arg=-Wl,-z,max-page-size=16384"
    }
    return env
}

val coreAndroid = coreAbis.map { (abi, target, _) ->
    tasks.register<Exec>("coreLib-$abi") {
        group = "rust core"; description = "cargo build tvbro-ffi (release) for $abi"
        inputs.files(coreSources)
        val so = File(coreDir, "target/$target/release/libtvbro_ffi.so")
        outputs.file(so)
        workingDir = coreDir
        commandLine(cargoExe, "build", "--locked", "-p", "tvbro-ffi", "--release", "--target", target)
        doFirst { environment(cargoEnv()) }
    }
}
val coreLibs = tasks.register<Sync>("coreLibs") {
    group = "rust core"; description = "libtvbro_ffi.so of every ABI into the APK's jniLibs"
    dependsOn(coreAndroid)
    for ((abi, target, _) in coreAbis) from(File(coreDir, "target/$target/release/libtvbro_ffi.so")) { into(abi) }
    into(coreOut.map { it.dir("jniLibs") })
}
// host build: the library the Kotlin bindings are generated from, and the one the JVM comparison tests load
val hostLib = File(coreDir, "target/debug/" + if (isWindows) "tvbro_ffi.dll" else "libtvbro_ffi.so")
val coreHost = tasks.register<Exec>("coreHost") {
    group = "rust core"; description = "cargo build tvbro-ffi for this machine (bindgen input, JVM tests)"
    inputs.files(coreSources)
    outputs.file(hostLib)
    workingDir = coreDir
    commandLine(cargoExe, "build", "--locked", "-p", "tvbro-ffi")
}
val coreBindings = tasks.register<Exec>("coreBindings") {
    group = "rust core"; description = "UniFFI Kotlin bindings (io.github.kmmarin.tvbro.core), generated, not committed"
    dependsOn(coreHost)
    inputs.file(hostLib)
    inputs.file(File(coreDir, "tvbro-ffi/uniffi.toml"))
    val out = coreOut.get().dir("kotlin").asFile
    outputs.dir(out)
    workingDir = coreDir
    commandLine(cargoExe, "run", "--locked", "-q", "-p", "tvbro-ffi", "--features", "bindgen", "--bin", "uniffi-bindgen", "--",
        "generate", "--library", hostLib.path, "--language", "kotlin", "--no-format", "--out-dir", out.path)
    doFirst { out.deleteRecursively() }
}
tasks.named("preBuild") { dependsOn(coreLibs, coreBindings) }

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
    // UniFFI's generated Kotlin calls the Rust core through JNA (LGPL-2.1 / Apache-2.0 dual licence, unmodified)
    implementation("net.java.dev.jna:jna:5.15.0@aar")

    constraints {
        // jcifs-ng 2.1.10 pulls bcprov 1.76 (CVE-2024-29857, CVE-2024-30171, CVE-2024-30172, CVE-2025-8916)
        implementation("org.bouncycastle:bcprov-jdk18on:1.80") { because("security fixes over the 1.76 that jcifs-ng asks for") }
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3") // Kotlin's SearchIndex in the core parity test (FTS4 like Android's SQLite)
    testImplementation("net.java.dev.jna:jna:5.15.0") // JNA with the desktop dispatch libraries for the JVM tests
}
