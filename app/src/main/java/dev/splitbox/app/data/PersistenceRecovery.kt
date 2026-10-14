// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import kotlinx.coroutines.CancellationException

/** Действие и его UI-завершение повторяются только явно; новый пользовательский запрос отменяет старое намерение. */
class RetryablePersistenceAction(private val onFailure: (String) -> Unit) {
    private var pending: (suspend () -> Unit)? = null
    fun discard() { pending = null }
    suspend fun run(action: suspend () -> Unit) {
        pending = action
        try {
            action()
            if (pending === action) pending = null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { onFailure(e.message ?: "Не удалось сохранить состояние") }
    }
    suspend fun retry() { pending?.let { run(it) } }
}

/** Повтор не зависит от нового события StateFlow и никогда не запускает установку. Потребители проверяют точный снимок под mutex. */
suspend fun retryFinishedResults(current: () -> PendingInstall?, retained: () -> List<PendingInstall>,
    consumeCurrent: suspend (PendingInstall) -> Unit, consumeRetained: suspend (PendingInstall) -> Unit,
    onFailure: (Exception) -> Unit) {
    suspend fun attempt(record: PendingInstall, consume: suspend (PendingInstall) -> Unit) {
        try { consume(record) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { onFailure(e) }
    }
    current()?.takeIf { it.state == "FINISHED" }?.let { attempt(it, consumeCurrent) }
    retained().filter { it.state == "FINISHED" }.forEach { attempt(it, consumeRetained) }
}
