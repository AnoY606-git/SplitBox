// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import dev.splitbox.app.model.OperationResult
import dev.splitbox.core.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class PreparedStorageCompletionTest {
    @Test fun successfulInstallWithUncopiedObbKeepsCheckpointApksAndDurablePointer() = runTest {
        val directory = Files.createTempDirectory("splitbox-obb-retention").toFile()
        val base = directory.resolve("base.apk").apply { writeText("APK") }
        val obb = directory.resolve("main.1.dev.test.game.obb").apply { writeText("OBB") }
        val checkpoint = directory.resolve("checkpoint.json").apply { writeText("saved checkpoint") }
        val metadata = ApkMetadata("dev.test.game", 1, "1", 34, 36)
        val prepared = PreparedPackage("game.xapk", metadata, listOf(ApkEntry(base, "base.apk", metadata, 3)),
            listOf(ObbEntry(obb, obb.name, 3)), emptyList(), directory)
        var memoryPointer: String? = null
        var diskPointer: String? = null
        try {
            val retained = finishPreparedStorage(OperationResult(true, "Установлено", obbWarning = "Экспортируйте OBB"),
                prepared, null, true, { memoryPointer = it }, { memoryPointer = null }, { diskPointer = memoryPointer },
                { check(it.deleteRecursively()) })
            assertTrue(retained)
            assertEquals(directory.absolutePath, diskPointer)
            assertEquals("saved checkpoint", checkpoint.readText())
            assertEquals("APK", base.readText())
            assertEquals("OBB", obb.readText())
        } finally { directory.deleteRecursively() }
    }
    @Test fun failedInstallStillClearsPointerAndHonorsCleanup() = runTest {
        val directory = Files.createTempDirectory("splitbox-clean-completion").toFile()
        directory.resolve("source.bin").writeText("source")
        var pointer: String? = directory.absolutePath
        var flushed = false
        val retained = finishPreparedStorage(OperationResult(false, "Отказ"), null, directory, true,
            { pointer = it }, { pointer = null }, { flushed = true }, { assertTrue(flushed); check(it.deleteRecursively()) })
        assertFalse(retained)
        assertNull(pointer)
        assertFalse(directory.exists())
    }
}
