// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

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
        val cleanupDetail = cleanupError?.let { "Не удалось удалить временные данные: ${it.message}. Очистите кэш в настройках." }
        if (cancelled && cleanupDetail == null) return@withContext UiState(title = "Операция отменена")
        val detail = listOfNotNull(if (cancelled) "Операция отменена" else error.message ?: "Неизвестная ошибка", cleanupDetail).joinToString(". ")
        UiState(OperationPhase.ERROR, if (cancelled) "Очистка не завершена" else "Не удалось подготовить файл", detail,
            result = OperationResult(false, detail))
    }
}
