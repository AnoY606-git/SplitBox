// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import dev.splitbox.core.message
import dev.splitbox.core.nestedMessage

import dev.splitbox.app.model.OperationPhase
import dev.splitbox.app.model.OperationResult
import dev.splitbox.app.model.UiState
import kotlinx.coroutines.*

/** Ошибка IO может прийти одновременно с отменой. Каталог всё равно должен быть удалён. */
internal suspend fun recoverFailedImport(error: Exception, dispatcher: CoroutineDispatcher = Dispatchers.IO,
    cleanup: suspend () -> Unit): UiState {
    val cancelled = error is CancellationException || !currentCoroutineContext().isActive
    return withContext(NonCancellable) {
        val cleanupError = try { withContext(dispatcher) { cleanup() }; null } catch (e: Exception) { e }
        val cleanupDetail = cleanupError?.let { message("import_failure_recovery_could_not_delete_temporary_data_clear_the", "Не удалось удалить временные данные: %1\$s. Очистите кэш в настройках.", nestedMessage(it.message)) }
        if (cancelled && cleanupDetail == null) return@withContext UiState(title = message("split_box_view_model_operation_canceled", "Операция отменена"))
        val detail = listOfNotNull(if (cancelled) message("split_box_view_model_operation_canceled", "Операция отменена") else error.message ?: message("import_failure_recovery_unknown_error", "Неизвестная ошибка"), cleanupDetail).joinToString(". ")
        UiState(OperationPhase.ERROR, if (cancelled) message("import_failure_recovery_cleanup_was_not_completed", "Очистка не завершена") else message("import_failure_recovery_could_not_prepare_the_file", "Не удалось подготовить файл"), detail,
            result = OperationResult(false, detail))
    }
}
