// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import dev.splitbox.app.model.UiState
import dev.splitbox.app.model.OperationPhase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal data class InstallJournalSnapshot(val ledger: SystemSessionLedger = SystemSessionLedger(),
    val prepared: String? = null, val permission: Triple<String, String, String>? = null) {
    /** Удаление разрешения и возврат указателя сохраняются одной ревизией журнала. */
    fun returnPermissionToReady(directory: String): InstallJournalSnapshot {
        if (permission?.third != directory || ledger.current != null ||
            (prepared != null && prepared != directory)) return this
        return copy(prepared = directory, permission = null)
    }
}

/** Завершение экспорта восстанавливает экран того же пакета. */
internal fun restoreAfterObbExport(before: UiState, current: UiState, checkpointOwner: String?,
    saved: SaveQueueState, validation: SelectionValidation): UiState {
    if (current.phase != OperationPhase.COPYING || current.prepared !== before.prepared ||
        current.selectedPaths != before.selectedPaths) return current
    if (before.prepared?.workDir?.absolutePath != checkpointOwner) return before
    val validated = validation.revision > 0 && validation.selected == before.selectedPaths
    return before.copy(savingSelection = saved.saving,
        // Ошибка журнала перед установкой не принадлежит очереди выбора и не исчезает от экспорта.
        selectionSaveError = saved.error ?: before.selectionSaveError.takeUnless { before.savingSelection },
        selectionValidationPending = if (validated) validation.pending else before.selectionValidationPending,
        selectionError = if (validated) validation.error else before.selectionError)
}

/** Один finally обслуживает успех, ошибку и отмену; поздний итог Android сохраняет приоритет. */
internal suspend fun runObbExport(before: UiState, ui: MutableStateFlow<UiState>, checkpointOwner: () -> String?,
    saved: () -> SaveQueueState, validation: () -> SelectionValidation, export: suspend () -> Unit) {
    ui.update { it.copy(phase = OperationPhase.COPYING, title = "Экспорт OBB", progress = null, canCancel = true) }
    try { export() }
    finally { ui.update { restoreAfterObbExport(before, it, checkpointOwner(), saved(), validation()) } }
}

/** Экспорт принадлежит локальной задаче, даже если журнал хранит неопределённую установку. */
internal fun cancelObbExport(state: UiState, operation: Job?): Boolean {
    if (state.phase != OperationPhase.COPYING || operation?.isActive != true) return false
    operation.cancel()
    return true
}
