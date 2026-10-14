// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import dev.splitbox.app.model.OperationResult

/** Доказанный итог живёт до успешного сохранения и очистки, даже если диск содержит лишь COMMITTING. Методы записи вызываются на IO. */
class PrivilegedResultPersistence(private val store: PrivilegedOperationStore,
    private val saveHistory: (PrivilegedOperationRecord, OperationResult?) -> Unit) {
    private val known = linkedMapOf<String, PrivilegedOperationRecord>()
    @Synchronized fun remember(record: PrivilegedOperationRecord, result: OperationResult): PrivilegedOperationRecord =
        record.copy(phase = PrivilegedOperationPhase.FINISHED, success = result.success,
            message = result.message, obbWarning = result.obbWarning).also { known[it.operationId] = it }
    @Synchronized fun pending(): List<PrivilegedOperationRecord> = known.values.toList()
    @Synchronized fun persist(record: PrivilegedOperationRecord) {
        val final = known[record.operationId] ?: record
        // История тоже может сохранить доказательство, когда файл операции недоступен.
        val written = runCatching { store.write(final) }
        val history = runCatching { if (final.saveHistory) saveHistory(final, (final.recoveryDecision() as? PrivilegedRecoveryDecision.Known)?.result) }
        written.getOrThrow(); history.getOrThrow()
    }
    @Synchronized fun clear(operationId: String) {
        store.clear(operationId)
        known.remove(operationId)
    }
    @Synchronized fun archive() {
        // Сначала завершаем известные в этом процессе записи; чтение старого файла не понижает доказанный итог до UNKNOWN.
        known.values.toList().forEach { persist(it); clear(it.operationId) }
        val record = store.read() ?: return
        if (record.saveHistory) saveHistory(record, (record.recoveryDecision() as? PrivilegedRecoveryDecision.Known)?.result)
        store.clear(record.operationId)
    }
}
