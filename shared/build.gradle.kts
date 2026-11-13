// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
// Platform-neutral data layer shared by the Android TV app and the desktop app:
// models, M3U / Xtream / XMLTV parsers, catch-up URLs, VOD parsing and episode title cleaning.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // XmlPullParser API: provided by Android itself; the desktop app adds kXML2 at runtime.
    compileOnly("xmlpull:xmlpull:1.1.3.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3") // FTS4 like Android's framework SQLite, for index tests
}
