plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

import java.util.Properties

fun signingProp(name: String): String? {
    val fromProject = findProperty(name)?.toString()?.takeIf { it.isNotBlank() }
    if (fromProject != null) return fromProject
    val fromEnv = System.getenv(name)?.takeIf { it.isNotBlank() }
    if (fromEnv != null) return fromEnv
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.isFile) {
        val props = Properties()
        propsFile.inputStream().use { stream -> props.load(stream) }
        return props.getProperty(name)?.takeIf { it.isNotBlank() }
    }
    return null
}

android {
    namespace = "net.z841973620.colorosliquidglass"
    // Backdrop 2.0/Compose 1.11 needs newer compile stubs. Runtime compatibility remains Android 13.
    compileSdk = 35

    defaultConfig {
        applicationId = "net.z841973620.colorosliquidglass"
        minSdk = 33
        targetSdk = 33
        versionCode = 11
        versionName = "0.4.1"
    }

    val storeFilePath = signingProp("RELEASE_STORE_FILE")
    val storePasswordValue = signingProp("RELEASE_STORE_PASSWORD")
    val keyAliasValue = signingProp("RELEASE_KEY_ALIAS")
    val keyPasswordValue = signingProp("RELEASE_KEY_PASSWORD")
    val hasReleaseSigning = listOf(
        storeFilePath, storePasswordValue, keyAliasValue, keyPasswordValue
    ).all { !it.isNullOrBlank() }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(storeFilePath!!)
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // AGP 8.7 lint crashes on Kotlin 2.4 UAST/Compose bytecode; compilation and packaging remain gated.
    lint { checkReleaseBuilds = false }
    packaging {
        resources.excludes += setOf(
            "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json",
            "META-INF/*.version",
            "META-INF/**/LICENSE.txt"
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:101.0.1")
    // Official libxposed service/interface binaries; only AAR minCompileSdk metadata is lowered.
    implementation(files("libs/libxposed-service-101.0.0-compat.aar"))
    implementation(files("libs/libxposed-interface-101.0.0-compat.aar"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.compose.foundation:foundation:1.11.1")
    implementation("org.jetbrains.compose.ui:ui:1.11.1")
    implementation("org.jetbrains.compose.ui:ui-graphics:1.11.1")
    // Upstream Backdrop/Shapes binaries, with only AAR minCompileSdk metadata lowered to
    // the locally available API 35. Runtime remains Android 13+ and the library code is unchanged.
    implementation(files("libs/backdrop-android-2.0.0-compat.aar"))
    implementation(files("libs/shapes-android-1.2.0-compat.aar"))
    implementation("org.jetbrains:annotations:26.1.0")
}
