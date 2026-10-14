// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import dev.splitbox.app.model.OperationResult

/** Переходы фаз вызываются на IO; доказанный ответ PM нельзя отменить ошибкой последующей записи. */
class PrivilegedOperationTracker(private val store: PrivilegedOperationStore) {
    var current: PrivilegedOperationRecord? = null
        private set
    var hasCommitProof = false
        private set
    fun begin(record: PrivilegedOperationRecord) { store.write(record); current = record; hasCommitProof = false }
    private fun transition(transform: (PrivilegedOperationRecord) -> PrivilegedOperationRecord) {
        val next = transform(checkNotNull(current) { "Операция установки ещё не создана" })
        store.write(next); current = next
    }
    fun sessionCreated(id: Int) = transition { it.copy(sessionId = id, phase = PrivilegedOperationPhase.WRITING) }
    fun commitStarted() = transition { it.copy(phase = PrivilegedOperationPhase.COMMITTING) }
    fun committed(): String? {
        hasCommitProof = true
        val value = checkNotNull(current) { "Операция установки ещё не создана" }.copy(
            phase = PrivilegedOperationPhase.COMMITTED, success = true, message = "Установка APK подтверждена Package Manager")
        current = value
        return try { store.write(value); null }
        catch (e: Exception) { "APK установлен, но подтверждение не записано: ${e.message}" }
    }
    fun finished(result: OperationResult) = transition { it.copy(phase = PrivilegedOperationPhase.FINISHED,
        success = result.success, message = result.message, obbWarning = result.obbWarning) }
    fun unknown(message: String) = transition { it.copy(phase = PrivilegedOperationPhase.UNKNOWN,
        success = null, message = message) }
}
