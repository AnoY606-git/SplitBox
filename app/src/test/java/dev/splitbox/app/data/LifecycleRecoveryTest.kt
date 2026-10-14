// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LifecycleRecoveryTest {
    @Test fun coldViewAndSendPreemptResumeWaitingForBootstrapExactlyOnce() = runTest {
        for (intent in listOf("VIEW", "SEND")) {
            val bootstrap = CompletableDeferred<Unit>()
            val recovery = LifecycleRecovery(backgroundScope)
            var shown = "Пусто"
            var imports = 0
            val completed = CompletableDeferred<Unit>()
            recovery.deferImport(bootstrap::await, {
                val handoff = recovery.preemptForImport(operationActive = false, current = null)!!
                launch {
                    handoff.awaitPrevious()
                    shown = intent; imports++; completed.complete(Unit)
                }
            }, { throw it })
            recovery.start { bootstrap.await(); yield(); shown = "Старый READY" }
            runCurrent(); bootstrap.complete(Unit); completed.await(); runCurrent()
            assertEquals(intent, shown); assertEquals(1, imports)
        }
    }
    @Test fun deferredBlockedViewAndSendReplayPendingSystemRecoveryWithoutAnotherResume() = runTest {
        for (intent in listOf("VIEW", "SEND")) for (phase in listOf("WRITING", "COMMITTED")) {
            val bootstrap = CompletableDeferred<Unit>()
            val recovery = LifecycleRecovery(backgroundScope)
            var ledger = SystemSessionLedger(PendingInstall(7, "dev.example", "1", "Пример", "$intent.apk",
                "/private/old", 1, true, true, state = phase))
            var imports = 0
            var reconciliations = 0
            var abandoned = 0
            recovery.deferImport(bootstrap::await, {
                if (recovery.preemptForImport(operationActive = false, current = ledger.current) != null) imports++
            }, { throw it })
            recovery.start {
                bootstrap.await()
                reconciliations++
                val stored = ledger.current!!
                if (stored.state == "WRITING") {
                    abandoned++
                    ledger = ledger.recordResult(stored.sessionId, false, "Подготовка сессии была прервана")
                } else ledger = ledger.missingSession(stored.sessionId)
            }
            runCurrent(); assertEquals(0, reconciliations)
            bootstrap.complete(Unit); runCurrent()
            assertEquals("$intent / $phase", 0, imports)
            assertEquals("$intent / $phase", 1, reconciliations)
            assertEquals(if (phase == "WRITING") "FINISHED" else "UNKNOWN", ledger.current?.state)
            assertEquals(if (phase == "WRITING") 1 else 0, abandoned)
            assertEquals(if (phase == "WRITING") false else null, ledger.current?.success)
        }
    }
    @Test fun importWaitsForCancelledRestoreIoAndBlockingOperationCannotBePreempted() = runTest {
        val recovery = LifecycleRecovery(backgroundScope)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var shown = "Пусто"
        recovery.start { withContext(NonCancellable) { started.complete(Unit); release.await(); shown = "Старый READY" } }
        started.await()
        assertNull(recovery.preemptForImport(operationActive = true, current = null))
        for (state in listOf("WRITING", "COMMITTED", "FINISHED")) {
            val current = PendingInstall(7, "dev.example", "1", "Пример", "base.apk", "/private/old", 1,
                true, true, state = state)
            assertNull(recovery.preemptForImport(operationActive = false, current = current))
        }
        assertTrue(recovery.isActive)
        val handoff = recovery.preemptForImport(operationActive = false, current = null)!!
        val imported = launch { handoff.awaitPrevious(); shown = "Новый URI" }
        runCurrent(); assertFalse(imported.isCompleted)
        release.complete(Unit); imported.join()
        assertEquals("Новый URI", shown)
    }
    @Test fun failedPreHandoffJournalWriteThenRetryRestoresPointerAndLatestCheckpointTogether() = runTest {
        data class Pointer(val directory: String?, val permission: Boolean = false)
        val dispatcher = StandardTestDispatcher(testScheduler)
        var diskPointer = Pointer(null)
        var memoryPointer = diskPointer
        var diskSelection = emptySet<String>()
        var failJournal = false
        var backendCalls = 0
        val checkpoints = RevisionedSaveQueue(backgroundScope, dispatcher) { value: Set<String> -> diskSelection = value }
        val journal = RevisionedSaveQueue(backgroundScope, dispatcher) { value: Pointer ->
            if (failJournal) throw IOException("Нет места")
            diskPointer = value
        }
        fun pointer(value: Pointer) { memoryPointer = value; journal.submit(value) }
        pointer(Pointer("/private/package")); checkpoints.submit(setOf("base")); checkpoints.flush(); journal.flush()
        failJournal = true
        pointer(Pointer(null))
        try { journal.flush(); backendCalls++ } catch (_: IOException) { }
        val attempt = runCatching {
            restoreReadyCheckpoint({ true }, { pointer(Pointer("/private/package")) }, checkpoints::flush, journal::flush)
        }
        assertTrue(attempt.isFailure)
        assertEquals("/private/package", memoryPointer.directory)
        checkpoints.submit(setOf("base", "split"))
        failJournal = false; checkpoints.retry(); journal.retry(); checkpoints.flush(); journal.flush()
        // Новый процесс получает только подтверждённые значения двух хранилищ.
        assertEquals("/private/package", diskPointer.directory)
        assertEquals(setOf("base", "split"), diskSelection)
        assertFalse(diskPointer.permission); assertEquals(0, backendCalls)
    }
    @Test fun replacedOwnerOrStartedBackendCannotRestoreOldReadyPointer() = runTest {
        for ((currentOwner, backendStarted) in listOf("new" to false, "old" to true)) {
            var restored = false
            assertFalse(restoreReadyCheckpoint({ currentOwner == "old" && !backendStarted }, { restored = true }, {}, {}))
            assertFalse(restored)
        }
    }
}
