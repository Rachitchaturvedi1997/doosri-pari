// feature-toolkit — plan section 8.2.
//
// Pension calculators and the DR table. Plan section 6.5.
// Pure functions, no I/O, unit tested against the plan's worked examples.
// A wrong DA calculation is a wrong number a pensioner acts on, so this
// module is deliberately the easiest one in the build to test.
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
            api(libs.kotlinx.datetime)

        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "in.doosripari.feature.toolkit"
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
