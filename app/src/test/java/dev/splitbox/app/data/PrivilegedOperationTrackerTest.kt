// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import dev.splitbox.app.model.OperationResult
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class PrivilegedOperationTrackerTest {
    private class Store : PrivilegedOperationStore {
        var record: PrivilegedOperationRecord? = null
        var fail = false
        override fun read() = record
        override fun write(record: PrivilegedOperationRecord) { if (fail) throw IOException("Нет места"); this.record = record }
        override fun clear(operationId: String) { if (record?.operationId == operationId) record = null }
    }
    private fun record() = PrivilegedOperationRecord("e6d62520-d81f-4304-a49f-4b0d027a7d8c", PrivilegedBackend.ROOT,
        0, "dev.example.fixture", "1", 1, "Пример", "base.apk", 1)
    @Test fun phasesAreDurableBeforeCommandsAndFinalResultIsCorrelated() {
        val store = Store(); val tracker = PrivilegedOperationTracker(store)
        tracker.begin(record()); assertEquals(PrivilegedOperationPhase.CREATING, store.record?.phase)
        tracker.sessionCreated(17); assertEquals(17, store.record?.sessionId)
        tracker.commitStarted(); assertEquals(PrivilegedOperationPhase.COMMITTING, store.record?.phase)
        assertNull(tracker.committed()); assertTrue(tracker.hasCommitProof)
        assertTrue(store.record!!.recoveryDecision() is PrivilegedRecoveryDecision.Known)
        tracker.finished(OperationResult(true, "Установлено", "dev.example.fixture"))
        assertEquals(PrivilegedOperationPhase.FINISHED, store.record?.phase)
        assertEquals(record().operationId, store.record?.operationId)
    }
    @Test fun diskFailureAfterProofCannotTurnSuccessIntoFailure() {
        val store = Store(); val tracker = PrivilegedOperationTracker(store)
        tracker.begin(record()); tracker.sessionCreated(17); tracker.commitStarted(); store.fail = true
        assertNotNull(tracker.committed())
        assertTrue(tracker.hasCommitProof)
        assertEquals(PrivilegedOperationPhase.COMMITTED, tracker.current?.phase)
        assertEquals(PrivilegedOperationPhase.COMMITTING, store.record?.phase)
    }
    @Test fun failureBeforeCommitStopsHandoffAndUnknownKeepsUuid() {
        val store = Store(); val tracker = PrivilegedOperationTracker(store)
        tracker.begin(record()); tracker.sessionCreated(17); store.fail = true
        try { tracker.commitStarted(); fail("Ожидалась ошибка диска") } catch (_: IOException) { }
        assertEquals(PrivilegedOperationPhase.WRITING, store.record?.phase)
        assertFalse(tracker.hasCommitProof)
        store.fail = false; tracker.unknown("Ответ не получен")
        assertEquals(record().operationId, store.record?.operationId)
        assertSame(PrivilegedRecoveryDecision.Unknown, store.record?.recoveryDecision())
    }
}
