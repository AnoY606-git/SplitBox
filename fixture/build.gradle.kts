// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
plugins { id("com.android.application") }
android {
    namespace = "dev.splitbox.fixture"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    defaultConfig { applicationId = "dev.splitbox.fixture"; minSdk = 34; targetSdk = 37; versionCode = providers.gradleProperty("splitbox.fixtureVersionCode").orElse("1").get().toInt(); versionName = providers.gradleProperty("splitbox.fixtureVersionName").orElse("1.0").get() }
    // Модуль создаёт только тестовые пакеты и не использует ключ издателя.
    buildTypes { release { signingConfig = signingConfigs.getByName("debug"); isMinifyEnabled = false } }
}
