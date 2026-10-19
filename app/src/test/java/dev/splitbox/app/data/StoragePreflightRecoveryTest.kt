// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import dev.splitbox.app.model.*
import dev.splitbox.core.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

class StoragePreflightRecoveryTest {
    @Test fun spaceFailureBeforeSessionRestoresReadyPointerAndExactSelectionWithoutReimport() = runTest {
        val directory = Files.createTempDirectory("splitbox-space-retry").toFile()
        val apk = directory.resolve("base.apk").apply { writeText("APK") }
        val checkpoint = directory.resolve("checkpoint.json").apply { writeText("checkpoint with selection") }
        val metadata = ApkMetadata("dev.test.game", 1, "1", 34, 36)
        val prepared = PreparedPackage("game.apks", metadata, listOf(ApkEntry(apk, "base.apk", metadata, 3)),
            emptyList(), emptyList(), directory)
        val before = UiState(phase = OperationPhase.READY, prepared = prepared,
            selectedPaths = setOf("base.apk", "feature/game.apk"), sha256 = "known hash", appLabel = "Игра")
        var pointer: String? = null
        var persistedPointer: String? = null
        var persistedSelection = emptySet<String>()
        try {
            val recovered = recoverStoragePreflight(before, InsufficientStorageException(9_663_676_417L, 1_073_741_824L),
                { true }, { pointer = directory.absolutePath }, { persistedSelection = before.selectedPaths },
                { persistedPointer = pointer })
            assertNotNull(recovered)
            assertEquals(OperationPhase.READY, recovered!!.phase)
            assertSame(prepared, recovered.prepared)
            assertEquals(before.selectedPaths, recovered.selectedPaths)
            assertEquals(before.selectedPaths, persistedSelection)
            assertEquals(directory.absolutePath, persistedPointer)
            assertEquals("known hash", recovered.sha256)
            assertNull(recovered.selectionSaveError)
            assertFalse(recovered.canCancel)
            assertNull(recovered.result)
            assertTrue(recovered.detail.contains("9663676417"))
            assertTrue(recovered.detail.contains("1073741824"))
            assertEquals("APK", apk.readText())
            assertEquals("checkpoint with selection", checkpoint.readText())
        } finally { directory.deleteRecursively() }
    }
    @Test fun failedJournalRestoreKeepsReadyAndReportsPersistenceFailureSeparately() = runTest {
        val before = UiState(phase = OperationPhase.READY, selectedPaths = setOf("base", "feature"))
        val recovered = recoverStoragePreflight(before, InsufficientStorageException(100, 0),
            { true }, {}, {}, { throw IOException("Журнал недоступен") })
        assertNotNull(recovered)
        assertEquals(OperationPhase.READY, recovered!!.phase)
        assertEquals(before.selectedPaths, recovered.selectedPaths)
        assertTrue(recovered.selectionSaveError!!.contains("Журнал недоступен"))
        assertTrue(recovered.detail.contains("Недостаточно свободного места"))
    }
    @Test fun changedOwnerCannotBeOverwrittenOrRestored() = runTest {
        var pointerWritten = false
        val recovered = recoverStoragePreflight(UiState(), InsufficientStorageException(100, 0),
            { false }, { pointerWritten = true }, {}, {})
        assertNull(recovered)
        assertFalse(pointerWritten)
    }
}
