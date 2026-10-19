// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import dev.splitbox.app.model.OperationPhase
import dev.splitbox.app.model.UiState

/** Возврат к выбранному пакету разрешён только до создания сессии и при прежнем владельце. */
internal suspend fun recoverStoragePreflight(before: UiState, error: InsufficientStorageException,
    isOwned: () -> Boolean, restorePointer: () -> Unit, flushSelection: suspend () -> Unit,
    flushJournal: suspend () -> Unit): UiState? {
    if (!isOwned()) return null
    val restored = runCatching { restoreReadyCheckpoint(isOwned, restorePointer, flushSelection, flushJournal) }
    if (!isOwned() || restored.getOrNull() == false) return null
    val saveError = restored.exceptionOrNull()?.let { "Не удалось сохранить состояние: ${it.message ?: "ошибка записи"}" }
    val detail = listOfNotNull(error.message,
        "Подготовленный пакет и выбор APK сохранены в кэше. После освобождения места нажмите «Установить» снова.", saveError).joinToString("\n")
    return before.copy(phase = OperationPhase.READY, title = "Недостаточно свободного места", detail = detail,
        progress = null, result = null, canCancel = false, pendingPackageName = null,
        savingSelection = false, selectionSaveError = saveError)
}
