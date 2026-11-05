// core-network — plan section 8.2.
//
// Ktor client, JWT auth with refresh, the one error shape, and retries.
// Plan section 11: every write is rate limited per user and per IP, and errors
// are always {"detail": ..., "errors": [...]}. Both live here so no feature
// module can invent its own.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    jvmToolchain(17)

    // A plain JVM target so shared logic is testable in milliseconds on a Linux
    // CI runner, with no Android emulator or Mac in the loop.
    jvm()

    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                }
            }
        }
    }

    // Plan section 8.1: the iOS app consumes this framework from SwiftUI.
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:core-model"))
            api(libs.ktor.client.core)
            api(libs.ktor.client.content.negotiation)
            api(libs.ktor.serialization.json)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)

        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "in.doosripari.network"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        // Plan section 13: Android 9 and later, 2 GB RAM phones.
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
