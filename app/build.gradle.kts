plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt.android)
}

val signingEnvironment = listOf("KEYSTORE_PATH", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
    .associateWith { providers.environmentVariable(it).orNull?.takeIf(String::isNotBlank) }
val hasReleaseSigning = signingEnvironment.values.all { it != null }
require(hasReleaseSigning || signingEnvironment.values.all { it == null }) {
    "Release signing requires all four variables: KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD"
}
val buildTag = providers.environmentVariable("GITHUB_REF").orNull
    ?.takeIf { it.startsWith("refs/tags/v") }?.removePrefix("refs/tags/v")
val ciVersionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull
    ?.toIntOrNull()?.takeIf { it in 1..2_100_000_000 } ?: 1

android {
    namespace = "com.alal.downloader"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.alal.downloader"
        minSdk = 24
        targetSdk = 35
        versionCode = ciVersionCode
        versionName = buildTag?.takeIf(String::isNotBlank) ?: "0.1.0"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(requireNotNull(signingEnvironment["KEYSTORE_PATH"]))
                storePassword = signingEnvironment["KEYSTORE_PASSWORD"]
                keyAlias = signingEnvironment["KEY_ALIAS"]
                keyPassword = signingEnvironment["KEY_PASSWORD"]
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

kapt {
    correctErrorTypes = true
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.hilt.android)
    kapt(libs.androidx.room.compiler)
    kapt(libs.hilt.compiler)
    testImplementation(libs.junit)
}