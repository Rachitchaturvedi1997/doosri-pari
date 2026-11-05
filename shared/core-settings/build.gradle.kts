// core-settings — plan section 8.2.
//
// Preferences, language and text scale (DataStore KMP).
// Plan section 4.3 sets a 20pt body and an in-app text scale on top of the
// system size. Both are read from here so the Android and iOS apps agree on
// what a 60+ user's text should measure.
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
            api(libs.datastore)
            api(libs.kotlinx.coroutines.core)

        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "in.doosripari.settings"
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
