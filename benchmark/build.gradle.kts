// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.splitbox.benchmark"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        minSdk = 34
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["splitbox.iterations"] =
            providers.gradleProperty("splitbox.benchmarkIterations").orElse("5").get()
        testInstrumentationRunnerArguments["splitbox.compilation"] =
            providers.gradleProperty("splitbox.benchmarkCompilation").orElse("None").get()
    }
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
        create("profileGeneration") {
            initWith(getByName("benchmark"))
            matchingFallbacks += listOf("release")
        }
    }
    // Единственный источник принадлежащих проекту бинарных fixtures, без копии в Git.
    sourceSets.getByName("main").assets.srcDir("../core/src/test/resources")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
androidComponents { beforeVariants(selector().all()) { it.enable = it.buildType in setOf("benchmark", "profileGeneration") } }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test:runner:1.6.2")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.core:core:1.16.0")
}

// AGP 8.13.2 не переносит атрибуты Android/Kotlin в TestedApks; Compose выбирает JVM stubs.
configurations.matching { it.name in setOf("benchmarkTestedApks", "profileGenerationTestedApks") }.configureEach {
    attributes.attribute(
        org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.attribute,
        org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm,
    )
    attributes.attribute(
        org.gradle.api.attributes.java.TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
        objects.named(org.gradle.api.attributes.java.TargetJvmEnvironment.ANDROID),
    )
}
