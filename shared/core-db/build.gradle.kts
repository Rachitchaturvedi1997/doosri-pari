// core-db — plan section 8.2.
//
// Offline cache, reminders and dose logs (SQLDelight).
// Plan section 8.2 offered Room KMP or SQLDelight; SQLDelight is chosen because
// Room's KMP support is still alpha and this code is not compiled again until
// there is a Mac and an emulator. Pension slips, documents and the document
// safe index live HERE and never leave the device (plan section 10).
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
            api(libs.sqldelight.runtime)
            api(libs.sqldelight.coroutines)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)

        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "in.doosripari.db"
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
