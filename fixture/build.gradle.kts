// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
plugins { id("com.android.application") }
android {
    namespace = "dev.splitbox.fixture"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig { applicationId = "dev.splitbox.fixture"; minSdk = 34; targetSdk = 36; versionCode = 1; versionName = "1.0" }
    // Модуль создаёт только тестовые пакеты и не использует ключ издателя.
    buildTypes { release { signingConfig = signingConfigs.getByName("debug"); isMinifyEnabled = false } }
}
