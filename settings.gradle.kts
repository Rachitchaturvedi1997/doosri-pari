// Doosri Pari — settings.
//
// The module tree is plan section 8.2 in full. Every module is registered even
// before it has much in it, so that `feature-sahayak` can depend on `core-model`
// the day it is written rather than the day the graph has to be reshuffled
// underneath it.
//
// The `core-*` modules are the ones Swift reaches through the framework;
// `feature-*` and `ui-canvas` are Kotlin-only and the iOS app re-implements
// their screens natively (plan section 8.1: "native interface, shared logic").
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        // Lets Gradle download the JDK 17 toolchain the shared modules target,
        // so nobody has to pre-install it. Moholla's Android half already
        // builds against Java 17 bytecode, so we keep 17 rather than bump.
        id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DoosriPari"

// --- shared Kotlin Multiplatform core ---------------------------------------
// core-* first: everything below depends on them.
include(":shared:core-model")
include(":shared:core-network")
include(":shared:core-db")
include(":shared:core-settings")
include(":shared:core-crypto")

// feature-*: one per plan section 6 area that carries state worth sharing.
include(":shared:feature-checkin")
include(":shared:feature-sahayak")
include(":shared:feature-toolkit")
include(":shared:feature-circles")
include(":shared:feature-seva")
include(":shared:feature-health")
include(":shared:feature-kendra")
include(":shared:feature-voice")

// The Compose Multiplatform drawing layer (plan section 8.2 ui-canvas).
include(":shared:ui-canvas")

// --- Android app -------------------------------------------------------------
include(":android:app")
