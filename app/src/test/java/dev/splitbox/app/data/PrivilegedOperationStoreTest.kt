// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class PrivilegedOperationStoreTest {
    private val id = "6072e935-6452-48da-95e9-dd7e791b4bfe"
    private fun record(phase: PrivilegedOperationPhase = PrivilegedOperationPhase.CREATING) = PrivilegedOperationRecord(
        operationId = id, backend = PrivilegedBackend.ROOT, userId = 0, packageName = "dev.test.game",
        versionName = "1.2", versionCode = 12, appLabel = "Игра 🎮", sourceName = "game.apks", createdAt = 1000,
        sessionId = if (phase == PrivilegedOperationPhase.CREATING) null else 42, phase = phase,
        success = if (phase == PrivilegedOperationPhase.FINISHED) true else null,
        message = if (phase == PrivilegedOperationPhase.FINISHED) "Установлено" else null, obbWarning = "OBB не перенесены")
    private fun inDirectory(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("splitbox-privileged-test").toFile()
        try { test(File(directory, "operation.bin")) } finally { directory.deleteRecursively() }
    }
    @Test fun everyPhaseSurvivesProcessStoreReplacement() = inDirectory { file ->
        PrivilegedBackend.entries.forEach { backend -> PrivilegedOperationPhase.entries.forEach { phase ->
            val expected = record(phase).copy(backend = backend)
            FilePrivilegedOperationStore(file).write(expected)
            assertEquals(expected, FilePrivilegedOperationStore(file).read())
        } }
    }
    @Test fun foreignUuidCannotDeleteCurrentOperation() = inDirectory { file ->
        val store = FilePrivilegedOperationStore(file)
        assertNull(store.read())
        store.write(record())
        store.clear("a64ca5c6-40d5-4ad0-b269-6d3d9f24af4b")
        assertEquals(record(), store.read())
        store.clear(id)
        assertNull(store.read())
    }
    @Test fun truncatedChangedOrOversizedFileIsCorruption() = inDirectory { file ->
        val store = FilePrivilegedOperationStore(file)
        store.write(record())
        val valid = file.readBytes()
        listOf(valid.copyOf(valid.size - 1), valid.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() },
            ByteArray(1024 * 1024), byteArrayOf()).forEach { bytes ->
            file.writeBytes(bytes)
            val error = runCatching { store.read() }.exceptionOrNull()
            assertTrue(error.toString(), error is IOException)
        }
    }
    @Test fun oversizedRecordDoesNotDestroyPreviousDurableRecord() = inDirectory { file ->
        val store = FilePrivilegedOperationStore(file)
        store.write(record())
        val error = runCatching { store.write(record().copy(message = "я".repeat(100000))) }.exceptionOrNull()
        assertTrue(error.toString(), error is IOException)
        assertEquals(record(), store.read())
        assertEquals(listOf(file.name), file.parentFile!!.list()!!.toList())
    }
    @Test fun recoveryNeedsProofAndDoesNotTreatInterruptedPhasesAsFailure() {
        listOf(PrivilegedOperationPhase.CREATING, PrivilegedOperationPhase.WRITING,
            PrivilegedOperationPhase.COMMITTING, PrivilegedOperationPhase.UNKNOWN).forEach { phase ->
            assertTrue(record(phase).recoveryDecision() is PrivilegedRecoveryDecision.Unknown)
        }
        val committed = record(PrivilegedOperationPhase.COMMITTED).recoveryDecision() as PrivilegedRecoveryDecision.Known
        assertTrue(committed.result.success)
        assertEquals("dev.test.game", committed.result.packageName)
        assertEquals("OBB не перенесены", committed.result.obbWarning)
        val failure = record(PrivilegedOperationPhase.FINISHED).copy(success = false, message = "Package Manager отказал")
            .recoveryDecision() as PrivilegedRecoveryDecision.Known
        assertFalse(failure.result.success)
        assertEquals("Package Manager отказал", failure.result.message)
    }
    @Test fun contradictoryTerminalProofIsRejectedWithoutReplacingRecord() = inDirectory { file ->
        val store = FilePrivilegedOperationStore(file)
        store.write(record())
        listOf(record(PrivilegedOperationPhase.COMMITTED).copy(success = false),
            record(PrivilegedOperationPhase.FINISHED).copy(success = null),
            record().copy(operationId = "чужой"), record().copy(sessionId = -1)).forEach { invalid ->
            assertTrue(runCatching { store.write(invalid) }.exceptionOrNull() is IOException)
            assertEquals(record(), store.read())
        }
    }
    @Test fun recoveryRetainsOriginalCleanupHistoryPolicyAndObbExportDirectory() = inDirectory { file ->
        val record = record(PrivilegedOperationPhase.COMMITTED).copy(cleanAfter = false, saveHistory = false, workDir = "/cache/операция-42")
        FilePrivilegedOperationStore(file).write(record)
        val restored = FilePrivilegedOperationStore(file).read()!!
        assertFalse(restored.cleanAfter)
        assertFalse(restored.saveHistory)
        assertEquals("/cache/операция-42", restored.workDir)
        assertTrue(restored.recoveryDecision() is PrivilegedRecoveryDecision.Known)
    }

}
