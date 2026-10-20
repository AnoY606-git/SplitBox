// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.installer

import dev.splitbox.app.data.*
import org.junit.Assert.*
import org.junit.Test

class SystemSessionRecoveryTest {
    private fun session(id: Int) = PendingInstall(id, "dev.test.game", "1", "Game", "game.apks",
        "/cache/session$id", 1000L + id, cleanAfter = true, saveHistory = true, state = "COMMITTED")
    @Test fun publicationWithoutAckIsDeliveredToReplacementCollector() {
        val delivery = ConfirmationDelivery()
        assertTrue(delivery.offer("42:1000"))
        assertFalse(delivery.offer("42:1000"))
        delivery.collectorStopped()
        assertTrue(delivery.offer("42:1000"))
    }
    @Test fun successfulActivityAckPreventsRotationDuplicate() {
        val delivery = ConfirmationDelivery()
        assertTrue(delivery.offer("42:1000"))
        delivery.acknowledge("42:1000", true)
        delivery.collectorStopped()
        assertFalse(delivery.offer("42:1000"))
    }
    @Test fun failedActivityLaunchCanBeRetriedOnResume() {
        val delivery = ConfirmationDelivery()
        assertTrue(delivery.offer("42:1000"))
        delivery.acknowledge("42:1000", false)
        assertTrue(delivery.offer("42:1000"))
    }
    @Test fun staleFailureAckDoesNotUndoSuccessfulLaunch() {
        val delivery = ConfirmationDelivery()
        delivery.offer("42:1000")
        delivery.acknowledge("42:1000", true)
        delivery.acknowledge("42:1000", false)
        delivery.collectorStopped()
        assertFalse(delivery.offer("42:1000"))
    }
    @Test fun missingSessionIsUnknownAndPreservesCallbackCorrelation() {
        val ledger = SystemSessionLedger(current = session(42)).missingSession(42)
        assertEquals("UNKNOWN", ledger.current!!.state)
        assertNull(ledger.current!!.success)
        assertFalse(ledger.current!!.blocksNewOperation)
        assertNotNull(ledger.find(42))
        assertEquals("system:42:1042", ledger.current!!.historyId)
    }
    @Test fun lateSuccessAfterMissingSessionIsOneRealFinalResult() {
        val unknown = SystemSessionLedger(current = session(42)).missingSession(42)
        val result = unknown.recordResult(42, true, "Installed")
            .recordResult(42, true, "Duplicate broadcast")
        assertTrue(result.find(42)!!.success == true)
        assertEquals("Installed", result.find(42)!!.message)
        assertEquals(1, result.completedRecords.size)
        assertEquals("system:42:1042", result.completedRecords.single().historyId)
    }
    @Test fun oldLateSuccessDoesNotOverwriteNewActiveSession() {
        val ledger = SystemSessionLedger(current = session(42)).missingSession(42).begin(session(43))
            .recordResult(42, true, "Installed later")
        assertEquals(43, ledger.current!!.sessionId)
        assertEquals("COMMITTED", ledger.current!!.state)
        assertEquals(listOf(42), ledger.completedRecords.map { it.sessionId })
        val consumed = ledger.consume(42)
        assertNull(consumed.find(42))
        assertEquals(43, consumed.current!!.sessionId)
        assertTrue(consumed.recordResult(42, true, "Duplicate").completedRecords.isEmpty())
    }
    @Test fun sessionQueryRaceCannotOverwriteAlreadyReceivedFinalSuccess() {
        val ledger = SystemSessionLedger(current = session(42)).recordResult(42, true, "Installed").missingSession(42)
        assertEquals("FINISHED", ledger.current!!.state)
        assertTrue(ledger.current!!.success == true)
    }
    @Test fun failedConfirmationLaunchKeepsRetryPayloadWithoutInventingFailure() {
        val pending = session(42).copy(state = "CONFIRMATION", confirmation = "saved-intent")
        val ledger = SystemSessionLedger(current = pending).markUnknown(42, "Retry launch", true)
        assertEquals("UNKNOWN", ledger.current!!.state)
        assertEquals("saved-intent", ledger.current!!.confirmation)
        assertNull(ledger.current!!.success)
        assertTrue(ledger.completedRecords.isEmpty())
        assertFalse(ledger.current!!.blocksNewOperation)
    }
    @Test fun choosingAnotherFileRetainsUnknownCorrelationAfterReleasingActiveSlot() {
        val ledger = SystemSessionLedger(current = session(42)).missingSession(42).releaseUnknown()
        assertNull(ledger.current)
        assertEquals(42, ledger.find(42)!!.sessionId)
        val result = ledger.recordResult(42, true, "Installed")
        assertEquals(1, result.completedRecords.size)
        assertTrue(result.completedRecords.single().success == true)
    }
    @Test fun detachedUnknownSnapshotCannotRestoreOverNewImport() {
        val expected = session(42).copy(state = "UNKNOWN")
        val afterImportStarted = SystemSessionLedger(current = expected).releaseUnknown()
        assertFalse(afterImportStarted.isCurrent(expected))
        assertNotNull(afterImportStarted.find(42))
    }
    @Test fun delayedUnknownRestoreCannotOverwriteLateFinalResult() {
        val expected = session(42).copy(state = "UNKNOWN")
        val afterCallback = SystemSessionLedger(current = expected).recordResult(42, true, "Installed")
        assertFalse(afterCallback.isCurrent(expected))
        assertTrue(afterCallback.current!!.success == true)
    }
    @Test fun delayedQueryRejectsChangedStateEvenWithSameSessionIdentity() {
        val expected = session(42).copy(state = "UNKNOWN", confirmation = "old-intent")
        val latest = SystemSessionLedger(current = expected).recordConfirmation(42, "new-intent")
        assertFalse(latest.isCurrent(expected))
    }
    @Test fun replacementConfirmationWithEqualHashCannotReuseOldLaunchOrDeliveryToken() {
        // Разные поколения могут иметь одинаковый String.hashCode; старый запуск должен устареть.
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val original = session(42).copy(state = "CONFIRMATION", confirmation = "Aa")
        val replacement = SystemSessionLedger(current = original).recordConfirmation(42, "BB")
        val current = replacement.current!!
        assertNotEquals(original.confirmationToken, current.confirmationToken)
        assertFalse(replacement.isLaunchCurrent(original.confirmationToken))
        assertTrue(replacement.isLaunchCurrent(current.confirmationToken))

        val delivery = ConfirmationDelivery()
        assertTrue(delivery.offer(original.confirmationToken))
        delivery.acknowledge(original.confirmationToken, true)
        assertTrue(delivery.offer(current.confirmationToken))
    }
    @Test fun lateConfirmationCannotResurrectFinishedCurrentOrRetainedSession() {
        for (success in listOf(true, false)) {
            val finished = SystemSessionLedger(current = session(42))
                .recordConfirmation(42, "first-generation")
                .recordResult(42, success, "Подтверждённый итог")
            assertEquals(finished, finished.recordConfirmation(42, "late-generation"))
            assertNull(finished.current!!.confirmation)
            assertFalse(finished.isLaunchCurrent("late-generation"))

            val retained = finished.begin(session(43))
            assertEquals(retained, retained.recordConfirmation(42, "late-generation"))
            assertEquals("FINISHED", retained.find(42)!!.state)
            assertEquals(success, retained.find(42)!!.success)
            assertNull(retained.find(42)!!.confirmation)
            assertEquals(43, retained.current!!.sessionId)
        }
    }
    @Test fun successfulCurrentAcknowledgementRestoresUnknownConfirmationWithoutReplacingPayload() {
        val original = session(42).copy(state = "UNKNOWN", confirmation = "current-generation",
            message = "Системное окно не открылось")
        val acknowledged = SystemSessionLedger(current = original)
            .acknowledgeConfirmation(original.confirmationToken, true)
        assertEquals("CONFIRMATION", acknowledged.current!!.state)
        assertEquals(original.confirmation, acknowledged.current!!.confirmation)
        assertEquals(original.confirmationToken, acknowledged.current!!.confirmationToken)
        assertEquals(original.historyId, acknowledged.current!!.historyId)
        assertNull(acknowledged.current!!.success)
        assertTrue(acknowledged.retained.isEmpty())
    }
    @Test fun failedCurrentAcknowledgementKeepsConfirmationForRetryWithoutInventingResult() {
        val original = session(42).copy(state = "CONFIRMATION", confirmation = "current-generation")
        val acknowledged = SystemSessionLedger(current = original)
            .acknowledgeConfirmation(original.confirmationToken, false)
        assertEquals("UNKNOWN", acknowledged.current!!.state)
        assertEquals(original.confirmation, acknowledged.current!!.confirmation)
        assertEquals(original.confirmationToken, acknowledged.current!!.confirmationToken)
        assertTrue(acknowledged.current!!.message.isNotBlank())
        assertNull(acknowledged.current!!.success)
        assertTrue(acknowledged.completedRecords.isEmpty())
    }
    @Test fun staleAcknowledgementCannotChangeReplacementConfirmationGeneration() {
        // Между получением token A и ответом UI Android успевает прислать подтверждение B.
        val original = session(42).copy(state = "UNKNOWN", confirmation = "generation-A")
        val replacement = SystemSessionLedger(current = original).recordConfirmation(42, "generation-B")
        val replacementAwaitingRetry = replacement.markUnknown(42, "Ошибка запуска B", true)
        for (latest in listOf(replacement, replacementAwaitingRetry)) {
            for (launched in listOf(true, false)) {
                assertEquals("Поздний ack A не должен менять состояние или payload поколения B", latest,
                    latest.acknowledgeConfirmation(original.confirmationToken, launched))
            }
        }
    }
    @Test fun acknowledgementCannotResurrectFinishedOrArchivedConfirmation() {
        val original = session(42).copy(state = "UNKNOWN", confirmation = "old-generation")
        for (success in listOf(true, false)) {
            val finished = SystemSessionLedger(current = original).recordResult(42, success, "Итог Android")
            for (latest in listOf(finished, finished.begin(session(43)))) {
                for (launched in listOf(true, false)) {
                    assertEquals(latest, latest.acknowledgeConfirmation(original.confirmationToken, launched))
                }
            }
        }
        val archived = SystemSessionLedger(current = original).releaseUnknown().begin(session(43))
        for (launched in listOf(true, false)) {
            assertEquals(archived, archived.acknowledgeConfirmation(original.confirmationToken, launched))
        }
    }
    @Test fun sessionIdReuseWithDifferentCreationCannotAcceptOldContinuation() {
        val expected = session(42)
        val latest = SystemSessionLedger(current = expected.copy(createdAt = 9999L))
        assertFalse(latest.isCurrent(expected))
    }
    @Test fun queuedConfirmationForArchivedRecordCannotLaunch() {
        val expected = session(42).copy(state = "UNKNOWN", confirmation = "saved-intent")
        val afterImport = SystemSessionLedger(current = expected).releaseUnknown().begin(session(43))
        assertFalse(afterImport.isLaunchCurrent(expected.confirmationToken))
        assertNotNull(afterImport.find(42))
    }
    @Test fun queuedConfirmationRequiresCurrentRetryOrConfirmationState() {
        val expected = session(42).copy(confirmation = "saved-intent")
        assertFalse(SystemSessionLedger(current = expected).isLaunchCurrent(expected.confirmationToken))
        val confirmation = expected.copy(state = "CONFIRMATION")
        assertTrue(SystemSessionLedger(current = confirmation).isLaunchCurrent(confirmation.confirmationToken))
        assertTrue(SystemSessionLedger(current = confirmation.copy(state = "UNKNOWN")).isLaunchCurrent(confirmation.confirmationToken))
    }
    @Test fun unchangedCurrentRecordCanApplyCompletedRestoreOrQuery() {
        val expected = session(42).copy(state = "UNKNOWN", confirmation = "saved-intent")
        assertTrue(SystemSessionLedger(current = expected).isCurrent(expected))
    }
    @Test fun retainedUnknownRecordsAreBoundedWithoutBlockingNewWork() {
        var ledger = SystemSessionLedger()
        for (id in 1..102) ledger = ledger.begin(session(id)).missingSession(id)
        assertEquals(100, ledger.retained.size)
        assertNull(ledger.find(1))
        assertNotNull(ledger.find(2))
        assertNotNull(ledger.find(102))
        assertFalse(ledger.current!!.blocksNewOperation)
    }
}
