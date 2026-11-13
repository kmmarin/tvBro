// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.zip.ZipFile

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// -Ptarget=linux-arm64 builds the Raspberry Pi classpath (Skia for linux-arm64) instead of the host's.
val pi = (findProperty("target") as String?) == "linux-arm64"

dependencies {
    implementation(project(":shared"))
    implementation(if (pi) compose.desktop.linux_arm64 else compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    implementation("net.java.dev.jna:jna-platform:5.15.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("net.sf.kxml:kxml2:2.3.0") // XmlPullParser implementation for the shared XMLTV parser
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10") // SMB/CIFS network share for recordings (LGPL-2.1, unmodified library)
    implementation("org.xerial:sqlite-jdbc:3.46.1.3") // search index (FTS4), same engine as Android's SQLite
    constraints {
        // jcifs-ng 2.1.10 pulls bcprov 1.76: CVE-2024-29857, CVE-2024-30171, CVE-2024-30172 (fixed 1.78), CVE-2025-8916 (1.79)
        implementation("org.bouncycastle:bcprov-jdk18on:1.80") { because("security fixes over the 1.76 that jcifs-ng asks for") }
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation(compose.desktop.uiTestJUnit4) // headless UI tests of the TV screen's focus rules
}

tasks.test { systemProperty("tvbro.config", layout.buildDirectory.dir("test-config").get().asFile.path) }

compose.desktop {
    application {
        mainClass = "io.github.kmmarin.tvbro.desktop.MainKt"
        jvmArgs += listOf("-Xmx1g", "-Dfile.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "tvBro"
            packageVersion = "0.1.0"
            description = "tvBro IPTV player"
            vendor = "The tvBro authors"
            licenseFile.set(rootProject.file("LICENSE"))
            modules("java.naming", "jdk.crypto.ec", "java.net.http", "java.sql", "jdk.httpserver", "java.desktop")
            windows { menuGroup = "tvBro"; upgradeUuid = "6f3b8f63-2a43-4f7b-9a8e-2d6b0c1e7a51" }
        }
    }
}

// libmpv is never part of a tvBro download: the usual Windows builds are GPL and tvBro is Apache-2.0. Users add their
// own copy (docs/INSTALL.md); the app explains how when it cannot load one.
val libmpvName = Regex("(?i)(^|/)(lib)?mpv[^/]*\\.(dll|so(\\.[0-9]+)*|dylib)$")

// Windows download: out/tvbro-windows.zip = the app image (bundled JRE) + NOTICE, LICENSE, INSTALL.md, THIRD_PARTY.md.
tasks.register<Zip>("windowsZip") {
    dependsOn("createDistributable")
    archiveFileName.set("tvbro-windows.zip")
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("out"))
    from(layout.buildDirectory.dir("compose/binaries/main/app")) { exclude { libmpvName.containsMatchIn(it.relativePath.pathString) } }
    into("tvBro") { from(rootProject.files("NOTICE", "LICENSE", "docs/INSTALL.md", "docs/THIRD_PARTY.md")) }
    val zip = archiveFile
    doLast {
        ZipFile(zip.get().asFile).use { z ->
            val bad = z.entries().asSequence().map { it.name }.filter { libmpvName.containsMatchIn(it) }.toList()
            if (bad.isNotEmpty()) throw GradleException("libmpv must not be in the Windows zip: $bad")
            if (z.getEntry("tvBro/tvBro.exe") == null || z.getEntry("tvBro/INSTALL.md") == null) throw GradleException("tvBro.exe or INSTALL.md missing from the zip")
        }
    }
}

// Raspberry Pi app image: the app jar plus its runtime classpath; packaged with a JRE by packaging/pack_pi.py
// (which also refuses any libmpv: the Pi uses the system package, sudo apt install libmpv2).
tasks.register<Sync>("piLibs") {
    from(tasks.named("jar"))
    from(configurations.named("runtimeClasspath"))
    into(layout.buildDirectory.dir("pi/lib"))
}
