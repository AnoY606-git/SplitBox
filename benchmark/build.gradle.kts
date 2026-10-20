// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
plugins {
    id("com.android.test")
}
android {
    namespace = "dev.splitbox.benchmark"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    defaultConfig {
        minSdk = 34
        targetSdk = 37
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
    implementation("androidx.core:core:1.19.1")
}
