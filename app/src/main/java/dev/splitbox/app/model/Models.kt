// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.model

import dev.splitbox.core.message
import dev.splitbox.core.nestedMessage

import android.content.Intent
import dev.splitbox.core.PreparedPackage

enum class BackendMode { SYSTEM, ROOT, SHIZUKU }
enum class ThemeMode { SYSTEM, DARK, LIGHT }
/** Значения Android сохраняет в LocaleManager, независимо от рабочих сессий установки. */
enum class AppLanguage(val languageTags: String) {
    SYSTEM(""), RUSSIAN("ru"), ENGLISH("en");
    companion object {
        fun fromLanguageTags(tags: String): AppLanguage = when (tags.substringBefore(',').substringBefore('-')) {
            "ru" -> RUSSIAN
            "en" -> ENGLISH
            else -> SYSTEM
        }
    }
}
data class AppSettings(
    val backend: BackendMode = BackendMode.SYSTEM,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val theme: ThemeMode = ThemeMode.SYSTEM, val dynamicColors: Boolean = false,
    val autoSelectSplits: Boolean = true, val verifySignatures: Boolean = true,
    val computeSha256: Boolean = true, val maxExpandedGiB: Int = 0,
    val cleanAfterInstall: Boolean = true, val saveHistory: Boolean = true,
    val haptics: Boolean = true, val keepScreenOn: Boolean = true,
    val installObb: Boolean = false, val allowDowngrade: Boolean = false
)
enum class OperationPhase { IDLE, COPYING, ANALYZING, READY, INSTALLING, WAITING_USER, UNKNOWN, SUCCESS, ERROR }
data class OperationResult(val success: Boolean, val message: String,
    val packageName: String? = null, val obbWarning: String? = null)
data class UiState(
    val phase: OperationPhase = OperationPhase.IDLE, val title: String = message("models_ready_to_install", "Готов к установке"),
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
data class AccessState(val rootStatus: String = message("models_access_not_checked", "Доступ не проверен"), val rootGranted: Boolean = false,
    val shizukuRunning: Boolean = false, val shizukuGranted: Boolean = false,
    val shizukuStatus: String = message("split_box_view_model_shizuku_not_connected", "Shizuku не подключён"))
enum class NoticeAction { CHOOSE_FILE, RETRY_SAVE }
data class AppNotice(val text: String, val action: NoticeAction? = null)
sealed interface UiEvent {
    data class LaunchIntent(val intent: Intent, val acknowledgementToken: String? = null) : UiEvent
    data class Message(val text: String) : UiEvent
}
