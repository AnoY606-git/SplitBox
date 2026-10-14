// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import dev.splitbox.app.model.*
import dev.splitbox.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ReadyStateRecoveryTest {
    private val directory = "/cache/package"
    private val permission = Triple("/cache/package/source.bin", "game.apks", directory)
    private fun session() = PendingInstall(42, "dev.example.game", "1", "Игра", "game.apks", directory,
        42, true, true, state = "COMMITTED")
    private fun ready(): UiState {
        val metadata = ApkMetadata("dev.example.game", 1, "1", 34, 36)
        return UiState(phase = OperationPhase.READY,
            prepared = PreparedPackage("game.apks", metadata, emptyList(), emptyList(), emptyList(), File(directory)),
            selectedPaths = setOf("base.apk"), savingSelection = true, selectionValidationPending = true)
    }
    @Test fun leavingPermissionPersistsReadyPointerInTheSameSnapshot() = runTest {
        var disk = InstallJournalSnapshot(permission = permission)
        val writes = RevisionedSaveQueue<InstallJournalSnapshot>(backgroundScope, StandardTestDispatcher(testScheduler)) { disk = it }
        writes.submit(disk.returnPermissionToReady(directory))
        writes.flush()
        assertNull(disk.permission)
        assertEquals(directory, disk.prepared)
    }
    @Test fun permissionTransitionDoesNotReleaseSessionOrReplaceNewerPackage() {
        for (snapshot in listOf(InstallJournalSnapshot(SystemSessionLedger(session()), permission = permission),
            InstallJournalSnapshot(prepared = "/cache/new", permission = permission),
            InstallJournalSnapshot(permission = permission.copy(third = "/cache/new")))) {
            assertEquals(snapshot, snapshot.returnPermissionToReady(directory))
        }
    }
    @Test fun failedPermissionSaveCanRetryWithoutLosingReadyPointer() = runTest {
        var fail = true
        var disk = InstallJournalSnapshot(permission = permission)
        val writes = RevisionedSaveQueue<InstallJournalSnapshot>(backgroundScope, StandardTestDispatcher(testScheduler)) {
            if (fail) throw IOException("Нет места")
            disk = it
        }
        writes.submit(disk.returnPermissionToReady(directory))
        assertTrue(runCatching { writes.flush() }.isFailure)
        assertEquals(permission, disk.permission)
        fail = false; writes.retry(); writes.flush()
        assertEquals(directory, disk.prepared); assertNull(disk.permission)
    }
    @Test fun exportCompletionUsesCompletedSaveAndValidationInsteadOfFrozenFlags() = runTest {
        val before = ready()
        val release = CompletableDeferred<Unit>()
        val queue = RevisionedSaveQueue<Int>(backgroundScope, StandardTestDispatcher(testScheduler)) { release.await() }
        val validator = SelectionValidator(backgroundScope, StandardTestDispatcher(testScheduler)) { _, _ -> release.await() }
        queue.submit(1); validator.submit(emptyList(), before.selectedPaths)
        runCurrent(); release.complete(Unit); runCurrent()
        val restored = restoreAfterObbExport(before, before.copy(phase = OperationPhase.COPYING), directory, queue.state.value, validator.state.value)
        assertEquals(OperationPhase.READY, restored.phase)
        assertFalse(restored.savingSelection); assertFalse(restored.selectionValidationPending)
    }
    @Test fun exportRestoresRealSaveAndValidationFailures() = runTest {
        val before = ready()
        val queue = RevisionedSaveQueue<Int>(backgroundScope, StandardTestDispatcher(testScheduler)) { throw IOException("Нет места") }
        val validator = SelectionValidator(backgroundScope, StandardTestDispatcher(testScheduler)) { _, _ -> throw IOException("Неверный APK") }
        queue.submit(1); validator.submit(emptyList(), before.selectedPaths); runCurrent()
        val restored = restoreAfterObbExport(before, before.copy(phase = OperationPhase.COPYING), directory, queue.state.value, validator.state.value)
        assertEquals("Нет места", restored.selectionSaveError)
        assertEquals("Неверный APK", restored.selectionError)
        assertFalse(restored.selectionValidationPending)
    }
    @Test fun exportCompletionDoesNotOverwriteReplacementOwnerOrSelection() {
        val before = ready()
        for (current in listOf(ready().copy(phase = OperationPhase.IDLE), before.copy(phase = OperationPhase.COPYING, selectedPaths = setOf("new.apk")), before.copy(phase = OperationPhase.SUCCESS))) {
            assertEquals(current, restoreAfterObbExport(before, current, directory, SaveQueueState(), SelectionValidation()))
        }
    }
    @Test fun coldUnknownExportReconcilesAlreadyCompletedValidation() {
        val before = ready().copy(phase = OperationPhase.UNKNOWN, savingSelection = false)
        val result = restoreAfterObbExport(before, before.copy(phase = OperationPhase.COPYING), directory,
            SaveQueueState(), SelectionValidation(1, before.selectedPaths))
        assertFalse(result.selectionValidationPending)
        assertEquals(OperationPhase.UNKNOWN, result.phase)
    }
    @Test fun exportDoesNotClearSaveErrorOutsideSelectionQueue() {
        val before = ready().copy(savingSelection = false, selectionSaveError = "Журнал не сохранён")
        val result = restoreAfterObbExport(before, before.copy(phase = OperationPhase.COPYING), directory,
            SaveQueueState(), SelectionValidation(1, before.selectedPaths))
        assertEquals("Журнал не сохранён", result.selectionSaveError)
    }
    @Test fun exportOrchestrationReconcilesSuccessFailureAndCancellation() = runTest {
        for (outcome in listOf("success", "failure", "cancel")) {
            val before = ready()
            val ui = MutableStateFlow(before)
            val release = CompletableDeferred<Unit>()
            val queue = RevisionedSaveQueue<Int>(backgroundScope, StandardTestDispatcher(testScheduler)) { release.await() }
            val validator = SelectionValidator(backgroundScope, StandardTestDispatcher(testScheduler)) { _, _ -> release.await() }
            queue.submit(1); validator.submit(emptyList(), before.selectedPaths)
            val result = runCatching {
                runObbExport(before, ui, { directory }, { queue.state.value }, { validator.state.value }) {
                    assertEquals(OperationPhase.COPYING, ui.value.phase)
                    release.complete(Unit); runCurrent()
                    when (outcome) {
                        "failure" -> throw IOException("Нет места для ZIP")
                        "cancel" -> throw CancellationException("Отмена")
                    }
                }
            }
            assertEquals(outcome != "success", result.isFailure)
            assertEquals(OperationPhase.READY, ui.value.phase)
            assertFalse(ui.value.savingSelection); assertFalse(ui.value.selectionValidationPending)
        }
    }
    @Test fun cancellingExportCancelsLocalJobWithoutTouchingUnknownSession() = runTest {
        val before = ready().copy(phase = OperationPhase.UNKNOWN)
        val ui = MutableStateFlow(before)
        val exported = CompletableDeferred<Unit>()
        val operation = launch {
            runObbExport(before, ui, { directory }, { SaveQueueState() }, { SelectionValidation(1, before.selectedPaths) }) {
                exported.complete(Unit)
                awaitCancellation()
            }
        }
        exported.await()
        assertTrue(cancelObbExport(ui.value, operation))
        operation.join()
        assertTrue(operation.isCancelled)
        assertEquals(OperationPhase.UNKNOWN, ui.value.phase)
        val install = Job()
        assertFalse(cancelObbExport(before.copy(phase = OperationPhase.INSTALLING), install))
        assertTrue(install.isActive)
        install.cancel()
    }

}
