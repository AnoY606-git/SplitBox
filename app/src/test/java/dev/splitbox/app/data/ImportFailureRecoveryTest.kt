// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import dev.splitbox.app.model.OperationPhase
import dev.splitbox.app.model.UiState
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class ImportFailureRecoveryTest {
    @Test fun ioFailureAfterCancellationStillDeletesImportAndLeavesAnalysisScreen() = runTest {
        val directory = Files.createTempDirectory("splitbox-import-failure").toFile()
        directory.resolve("source.bin").writeText("source")
        var state = UiState(OperationPhase.ANALYZING, canCancel = true)
        try {
            val operation = launch {
                currentCoroutineContext().cancel()
                state = recoverFailedImport(IOException("Чтение прервано"), StandardTestDispatcher(testScheduler)) {
                    yield()
                    check(directory.deleteRecursively())
                }
            }
            operation.join()
            assertFalse(directory.exists())
            assertEquals(OperationPhase.IDLE, state.phase)
            assertFalse(state.canCancel)
        } finally { directory.deleteRecursively() }
    }
    @Test fun cleanupFailureIsReportedWithoutLeavingActiveOperation() = runTest {
        val state = recoverFailedImport(IOException("Источник недоступен"), StandardTestDispatcher(testScheduler)) {
            throw IOException("Файл занят")
        }
        assertEquals(OperationPhase.ERROR, state.phase)
        assertTrue(state.detail.contains("Источник недоступен"))
        assertTrue(state.detail.contains("Файл занят"))
        assertFalse(state.canCancel)
    }
}
