// core-crypto — plan section 8.2.
//
// expect/actual encryption: Keystore on Android, Keychain + Secure Enclave on iOS.
// Plan section 14. This module holds NO algorithm of its own — it delegates to
// the platform keystore. The one thing it does own is the rule that a key is
// never extractable and never leaves the device.
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

        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "in.doosripari.crypto"
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
