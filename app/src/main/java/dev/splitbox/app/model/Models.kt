// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.model

import android.content.Intent
import dev.splitbox.core.PreparedPackage

enum class BackendMode { SYSTEM, ROOT, SHIZUKU }
enum class ThemeMode { SYSTEM, DARK, LIGHT }
data class AppSettings(
    val backend: BackendMode = BackendMode.SYSTEM,
    val theme: ThemeMode = ThemeMode.SYSTEM, val dynamicColors: Boolean = false,
    val autoSelectSplits: Boolean = true, val verifySignatures: Boolean = true,
    val computeSha256: Boolean = true, val maxExpandedGiB: Int = 2,
    val cleanAfterInstall: Boolean = true, val saveHistory: Boolean = true,
    val haptics: Boolean = true, val keepScreenOn: Boolean = true,
    val installObb: Boolean = false, val allowDowngrade: Boolean = false
)
enum class OperationPhase { IDLE, COPYING, ANALYZING, READY, INSTALLING, WAITING_USER, UNKNOWN, SUCCESS, ERROR }
data class OperationResult(val success: Boolean, val message: String,
    val packageName: String? = null, val obbWarning: String? = null)
data class UiState(
    val phase: OperationPhase = OperationPhase.IDLE, val title: String = "Готов к установке",
    val detail: String = "", val progress: Float? = null,
    val prepared: PreparedPackage? = null,
    val selectedPaths: Set<String> = emptySet(), val sha256: String? = null,
    val appLabel: String? = null, val result: OperationResult? = null, val canCancel: Boolean = false, val pendingPackageName: String? = null,
    val savingSelection: Boolean = false, val selectionSaveError: String? = null,
    val selectionValidationPending: Boolean = false, val selectionError: String? = null,
    val copiedBytes: Long = 0, val sourceBytes: Long? = null
)
data class HistoryEntry(val id: String, val timestamp: Long, val appLabel: String,
    val packageName: String, val versionName: String, val fileName: String,
    val backend: BackendMode, val success: Boolean?, val message: String)
data class AccessState(val rootStatus: String = "Доступ не проверен", val rootGranted: Boolean = false,
    val shizukuRunning: Boolean = false, val shizukuGranted: Boolean = false,
    val shizukuStatus: String = "Shizuku не подключён")
enum class NoticeAction { CHOOSE_FILE, RETRY_SAVE }
data class AppNotice(val text: String, val action: NoticeAction? = null)
sealed interface UiEvent {
    data class LaunchIntent(val intent: Intent, val acknowledgementToken: String? = null) : UiEvent
    data class Message(val text: String) : UiEvent
}
