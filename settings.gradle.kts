// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "openiptv"
include(":app", ":shared", ":desktop")
