// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.splitbox.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "dev.splitbox.app"
        minSdk = 34
        targetSdk = 36
        versionCode = providers.gradleProperty("splitbox.versionCode").get().toInt()
        versionName = providers.gradleProperty("splitbox.versionName").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    val signingNames = listOf("SPLITBOX_KEYSTORE_PATH", "SPLITBOX_KEYSTORE_PASSWORD", "SPLITBOX_KEY_ALIAS", "SPLITBOX_KEY_PASSWORD")
    val signingValues = signingNames.associateWith { providers.environmentVariable(it).orNull }
    val hasReleaseSigning = signingValues.values.any { it != null }
    if (hasReleaseSigning) {
        require(signingValues.values.all { !it.isNullOrBlank() }) {
            "Для подписи релиза задайте все четыре переменные: SPLITBOX_KEYSTORE_PATH, SPLITBOX_KEYSTORE_PASSWORD, SPLITBOX_KEY_ALIAS, SPLITBOX_KEY_PASSWORD."
        }
        signingConfigs.create("publisher") {
            storeFile = rootProject.file(signingValues.getValue("SPLITBOX_KEYSTORE_PATH")!!)
            require(storeFile!!.isFile) { "Указанное хранилище ключей подписи не найдено." }
            storePassword = signingValues.getValue("SPLITBOX_KEYSTORE_PASSWORD")
            keyAlias = signingValues.getValue("SPLITBOX_KEY_ALIAS")
            keyPassword = signingValues.getValue("SPLITBOX_KEY_PASSWORD")
            storeType = providers.environmentVariable("SPLITBOX_KEYSTORE_TYPE").orElse("JKS").get()
        }
    }
    // Чистые исходники дают неподписанный релиз; ключ издателя подключается явно.
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("publisher")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("benchmark") {
            initWith(getByName("release"))
            applicationIdSuffix = ".benchmark"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += listOf("release")
        }
        create("profileGeneration") {
            initWith(getByName("benchmark"))
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += listOf("release")
        }
    }
    buildFeatures { compose = true; aidl = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources.excludes += setOf("/META-INF/AL2.0", "/META-INF/LGPL2.1") }
    lint { abortOnError = true; checkReleaseBuilds = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.android.tools.build:apksig:8.13.2")
    "benchmarkImplementation"("androidx.profileinstaller:profileinstaller:1.4.1")
    "profileGenerationImplementation"("androidx.profileinstaller:profileinstaller:1.4.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
