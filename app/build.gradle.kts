plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

// Derive version from git tag (v1.2.3 -> 1.2.3) or default to 0.1.0
val buildTag = providers.environmentVariable("GITHUB_REF").orNull
    ?.takeIf { it.startsWith("refs/tags/v") }?.removePrefix("refs/tags/v")
    ?: providers.environmentVariable("VERSION_TAG").orNull // Manual workflow_dispatch
val appVersionName = buildTag?.takeIf(String::isNotBlank) ?: "0.1.0"

// Monotonic versionCode from GITHUB_RUN_NUMBER or default to 1
val ciVersionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull
    ?.toIntOrNull()?.takeIf { it in 1..2_100_000_000 } ?: 1

// Check for release signing credentials (prefer env vars for CI)
val signingEnvironment = mapOf(
    "KEYSTORE_PATH" to providers.environmentVariable("KEYSTORE_PATH").orNull,
    "KEYSTORE_PASSWORD" to providers.environmentVariable("KEYSTORE_PASSWORD").orNull,
    "KEY_ALIAS" to providers.environmentVariable("KEY_ALIAS").orNull,
    "KEY_PASSWORD" to providers.environmentVariable("KEY_PASSWORD").orNull
)
val hasReleaseSigning = signingEnvironment.values.all { !it.isNullOrBlank() }

android {
    namespace = "com.alal.downloader"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.alal.downloader"
        minSdk = 24
        targetSdk = 35
        versionCode = ciVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    
    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/DEPENDENCIES",
                "META-INF/NOTICE",
                "META-INF/LICENSE",
                "META-INF/INDEX.LIST"
            )
        }
    }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                val keystorePath = signingEnvironment["KEYSTORE_PATH"]!!
                storeFile = rootProject.file(keystorePath)
                storePassword = signingEnvironment["KEYSTORE_PASSWORD"]
                keyAlias = signingEnvironment["KEY_ALIAS"]
                keyPassword = signingEnvironment["KEY_PASSWORD"]
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            } else {
                // Fall back to debug signing when no credentials (allows local assembleRelease)
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
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

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.withType<Test> {
    testLogging {
        events("passed", "skipped", "failed")
        showExceptions = true
        showCauses = true
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
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
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.hilt.android)
    ksp(libs.androidx.room.compiler)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
}