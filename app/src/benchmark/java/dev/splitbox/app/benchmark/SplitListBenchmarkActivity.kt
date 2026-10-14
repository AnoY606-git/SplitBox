// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.benchmark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.splitbox.app.model.AccessState
import dev.splitbox.app.model.AppSettings
import dev.splitbox.app.model.OperationPhase
import dev.splitbox.app.model.UiState
import dev.splitbox.app.ui.InstallScreen
import dev.splitbox.app.ui.theme.SplitBoxTheme
import dev.splitbox.core.ApkEntry
import dev.splitbox.core.ApkMetadata
import dev.splitbox.core.PreparedPackage
import java.io.File

/** Измеряет только настоящий UI на синтетической metadata; APK не создаются и не устанавливаются. */
class SplitListBenchmarkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val count = intent.getIntExtra("splitCount", 50)
        require(count in setOf(50, 500, 4096)) { "Допустимы только 50, 500 или 4096 частей" }
        val metadata = ApkMetadata("dev.splitbox.benchmark.dataset", 1, "1.0", 34, 36)
        val apks = List(count) { index ->
            val base = index == 0
            val path = if (base) "base.apk" else "feature_%04d.apk".format(java.util.Locale.ROOT, index)
            ApkEntry(
                file = File(cacheDir, "benchmark-ui-only/$path"), archivePath = path,
                metadata = if (base) metadata else metadata.copy(splitName = "feature_$index", isFeatureSplit = true),
                size = 1024L, locked = base, selectionReason = if (base) "Базовый APK" else "Тестовый модуль",
            )
        }
        val prepared = PreparedPackage("Синтетический UI: $count частей", metadata, apks,
            emptyList(), emptyList(), File(cacheDir, "benchmark-ui-only"))
        setContent {
            var selected by remember { mutableStateOf(apks.map { it.archivePath }.toSet()) }
            val settings = AppSettings(haptics = false)
            SplitBoxTheme(settings) {
                InstallScreen(
                    ui = UiState(phase = OperationPhase.READY, prepared = prepared, selectedPaths = selected,
                        appLabel = "Тест UI: $count частей"),
                    settings = settings, access = AccessState(),
                    onChooseFile = {},
                    // Любой случайный вызов установки завершает тест ошибкой до backend.
                    onInstall = { error("Установка синтетического набора запрещена") },
                    onCancel = {}, onReset = {},
                    onToggleSplit = { path ->
                        if (path != "base.apk") selected = if (path in selected) selected - path else selected + path
                    },
                    onOpenApp = {}, onExportObb = {}, onSettings = {}, onCheckStatus = {},
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                )
            }
        }
    }
}
