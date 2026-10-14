// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class SplitBoxBaselineProfile {
    @get:Rule val profile = BaselineProfileRule()

    @Test fun productionJourneys() = profile.collect(packageName = TARGET) {
        clearTarget()
        launchMain()
        navigateSettingsHistoryBack()
        importFixture(importIntent())
        // Синтетическая Activity отсутствует в release и не входит в профиль production.
    }
}
