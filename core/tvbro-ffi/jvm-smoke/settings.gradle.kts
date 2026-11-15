// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
// Standalone JVM smoke test of the generated UniFFI Kotlin bindings (not part of the app build).
pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "tvbro-ffi-jvm-smoke"
