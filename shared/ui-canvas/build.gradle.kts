// ui-canvas — plan section 8.2.
//
// The drawn layer: the yoga instructor, the breathing orb, the japa mala.
// Compose Multiplatform rather than plain Compose, because these are drawn in
// the shared module and consumed natively on both platforms — SwiftUI embeds
// the view through UIKit rather than reimplementing the artwork.
plugins {{
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlin.compose)
}}

kotlin {{
    jvmToolchain(17)
    jvm()

    androidTarget {{
        compilations.all {{
            compileTaskProvider.configure {{
                compilerOptions {{
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                }}
            }}
        }}
    }}

    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach {{ it.binaries.framework {{
        baseName = "DoosriCanvas"
        isStatic = true
    }} }}

    sourceSets {{
        commonMain.dependencies {{
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            api(project(":shared:core-model"))
        }}
        commonTest.dependencies {{
            implementation(kotlin("test"))
        }}
    }}
}}

android {{
    namespace = "in.doosripari.canvas"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {{
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }}

    compileOptions {{
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }}
}}
