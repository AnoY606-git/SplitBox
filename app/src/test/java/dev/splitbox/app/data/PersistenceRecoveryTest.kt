// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import dev.splitbox.app.model.OperationResult
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PersistenceRecoveryTest {
    @Test fun failedResetOrCacheFlushKeepsUiAndCanRetry() = runTest {
        for (action in listOf("Сброс", "Очистка кэша")) {
            var fail = true
            var ui = "Пакет"
            var deleted = false
            val notices = mutableListOf<String>()
            val retry = RetryablePersistenceAction { notices += it }
            retry.run {
                if (fail) throw IOException("Нет места")
                deleted = true
                ui = action
            }
            assertFalse(deleted); assertEquals("Пакет", ui); assertEquals(1, notices.size)
            fail = false; retry.retry()
            assertTrue(deleted); assertEquals(action, ui)
        }
    }
    @Test fun failedHistoryClearDoesNotEscapeAndReplacementCancelsOldRetry() = runTest {
        var history = listOf("Результат")
        val retry = RetryablePersistenceAction { }
        retry.run { throw IOException("Нет места") }
        assertEquals(1, history.size)
        retry.run { history = emptyList() }
        retry.retry()
        assertTrue(history.isEmpty())
        var stale = false
        retry.run { stale = true; throw IOException("Нет места") }
        stale = false; retry.discard(); retry.retry()
        assertFalse(stale)
    }
    @Test fun finishedCurrentAndRetainedRetryWithoutNewEmissionAndKeepHistoryId() = runTest {
        fun record(id: Int) = PendingInstall(id, "dev.example.fixture", "1", "Пример", "base.apk", "/cache/$id", 1,
            true, true, state = "FINISHED", success = true)
        var ledger = SystemSessionLedger(record(1), listOf(record(2)))
        val history = mutableMapOf<String, Boolean?>()
        var fail = true
        var errors = 0
        suspend fun consume(value: PendingInstall) {
            if (ledger.find(value.sessionId) != value) return
            if (fail) throw IOException("История недоступна")
            history[value.historyId] = value.success
            ledger = ledger.consume(value.sessionId)
        }
        suspend fun retry() = retryFinishedResults({ ledger.current }, { ledger.retained },
            { consume(it) }, { consume(it) }, { errors++ })
        retry(); assertEquals(2, errors); assertTrue(ledger.current!!.blocksNewOperation)
        fail = false; retry(); retry()
        assertNull(ledger.current); assertTrue(ledger.retained.isEmpty())
        assertEquals(setOf(record(1).historyId, record(2).historyId), history.keys)
    }
    private class Store : PrivilegedOperationStore {
        var record: PrivilegedOperationRecord? = null
        var failWrite = false
        var failClear = false
        override fun read() = record
        override fun write(record: PrivilegedOperationRecord) {
            if (failWrite) throw IOException("Нет места")
            this.record = record
        }
        override fun clear(operationId: String) {
            if (failClear) throw IOException("Нет места")
            if (record?.operationId == operationId) record = null
        }
    }
    @Test fun knownSuccessAndFailureSurviveDiskAndHistoryFailuresUntilArchive() {
        for (success in listOf(true, false)) {
            val store = Store()
            val record = PrivilegedOperationRecord("e6d62520-d81f-4304-a49f-4b0d027a7d8c", PrivilegedBackend.ROOT,
                0, "dev.example.fixture", "1", 1, "Пример", "base.apk", 1, phase = PrivilegedOperationPhase.COMMITTING)
            store.record = record
            var failHistory = true
            val history = mutableMapOf<String, Boolean?>()
            val results = PrivilegedResultPersistence(store) { value, result ->
                if (failHistory) throw IOException("История недоступна")
                history[value.operationId] = result?.success
            }
            val final = results.remember(record, OperationResult(success, "Доказанный итог", record.packageName))
            store.failWrite = true
            assertTrue(runCatching { results.persist(final) }.isFailure)
            assertEquals(1, results.pending().size)
            store.failWrite = false
            assertTrue(runCatching { results.archive() }.isFailure)
            failHistory = false; store.failClear = true
            assertTrue(runCatching { results.archive() }.isFailure)
            assertEquals(success, history[record.operationId]); assertEquals(1, results.pending().size)
            store.failClear = false; results.archive(); results.archive()
            assertEquals(mapOf(record.operationId to success), history)
            assertTrue(results.pending().isEmpty()); assertNull(store.record)
        }
    }
    @Test fun permissionContinuationWaitsForExactGenerationAndFreshOwner() = runTest {
        val release = CompletableDeferred<Unit>()
        val validator = SelectionValidator(backgroundScope, StandardTestDispatcher(testScheduler)) { _, _ -> release.await() }
        val token = validator.submit(emptyList(), setOf("base"))
        var fresh = true
        var continued = false
        val waiter = launch { continued = validator.awaitValid(token) { fresh } }
        runCurrent(); assertFalse(continued)
        fresh = false; release.complete(Unit); waiter.join()
        assertFalse(continued)
        val latest = validator.submit(emptyList(), setOf("base"))
        assertTrue(validator.awaitValid(latest) { true })
        assertFalse(validator.awaitValid(token) { true })
    }
    @Test fun newerSelectionNeverContinuesOldPermissionRequest() = runTest {
        val release = CompletableDeferred<Unit>()
        val validator = SelectionValidator(backgroundScope, StandardTestDispatcher(testScheduler)) { _, _ -> release.await() }
        val token = validator.submit(emptyList(), setOf("old"))
        val waiter = async { validator.awaitValid(token) { true } }
        runCurrent(); validator.submit(emptyList(), setOf("new")); release.complete(Unit)
        assertFalse(waiter.await())
    }
}
