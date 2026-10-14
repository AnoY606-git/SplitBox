// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import kotlinx.coroutines.*

/** Явный URI имеет приоритет над восстановлением экрана, но не над установкой. Вызывается на Main. */
class LifecycleRecovery(private val scope: CoroutineScope) {
    private var job: Job? = null
    private var pendingImport: Job? = null
    private var pendingResume: (suspend () -> Unit)? = null
    val isActive: Boolean get() = job?.isActive == true
    fun deferImport(awaitReady: suspend () -> Unit, onReady: () -> Unit, onFailure: (Exception) -> Unit) {
        val previous = pendingImport
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try { awaitReady(); onReady() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { onFailure(e) }
            finally {
                // Замена URI не должна завершить ожидание нового импорта старым Job.
                if (pendingImport === currentCoroutineContext().job) {
                    pendingImport = null
                    val resume = pendingResume
                    pendingResume = null
                    resume?.let(::start)
                }
            }
        }
        pendingImport = next
        previous?.cancel()
        next.start()
    }
    fun start(block: suspend () -> Unit) {
        if (pendingImport != null) {
            pendingResume = block
            return
        }
        if (isActive) return
        job = scope.launch { block() }
    }
    fun preemptForImport(operationActive: Boolean, current: PendingInstall?): ImportHandoff? {
        if (operationActive || current?.blocksNewOperation == true) return null
        // Только принятый импорт заменяет READY/permission recovery; отказ сохраняет обязательную проверку сессии.
        pendingResume = null
        val previous = job
        previous?.cancel()
        job = null
        return ImportHandoff(previous)
    }
    /** Восстановление завершилось; дальнейшая установка получает отдельный Job. */
    fun release() { job = null }
    fun cancel() {
        pendingResume = null
        val pending = pendingImport
        pendingImport = null
        job?.cancel(); pending?.cancel()
    }
    class ImportHandoff(private val previous: Job?) {
        suspend fun awaitPrevious() { previous?.join() }
    }
}

/** Возвращаем READY-владение только до передачи backend и лишь для исходного пакета. Ошибка остаётся доступной для явного retry. */
suspend fun restoreReadyCheckpoint(isOwned: () -> Boolean, restorePointer: () -> Unit,
    flushSelection: suspend () -> Unit, flushJournal: suspend () -> Unit): Boolean {
    if (!isOwned()) return false
    restorePointer()
    flushSelection()
    if (!isOwned()) return false
    flushJournal()
    return true
}
