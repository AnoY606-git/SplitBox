// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class MultiApkStagerTest {
    private fun fixture(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("copy-set").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
    @Test fun copiesEachStreamOnceWithCollisionSafeNamesAndAggregateProgress() = fixture { dir ->
        val progress = mutableListOf<Long>()
        val opened = mutableListOf<Int>()
        val entries = MultiApkStager.stage(listOf(0, 1), dir, 6, { "../../same.apk" },
            { opened += it; "abc".byteInputStream() }, { false }, progress::add)
        assertEquals(listOf(0, 1), opened)
        assertEquals(listOf(3L, 6L), progress)
        assertEquals(2, entries.map { it.archivePath }.distinct().size)
        assertEquals(listOf("0000.apk", "0001.apk"), entries.map { it.file.name })
        entries.forEach { assertEquals("abc", it.file.readText()); assertFalse(it.archivePath.contains("..")) }
        assertEquals(2, dir.listFiles()!!.size)
    }
    @Test fun actualBytesAcrossUnknownSizeSourcesEnforceBudgetAndCleanWorkDirectory() = fixture { dir ->
        assertThrows(IOException::class.java) {
            MultiApkStager.stage(listOf(0, 1), dir, 5, { "app.apk" }, { "abc".byteInputStream() }, { false }, {})
        }
        assertFalse(dir.exists())
    }
    @Test fun cancellationDuringSecondStreamCleansOwnDirectory() = fixture { dir ->
        var cancelled = false
        assertThrows(CancellationException::class.java) {
            MultiApkStager.stage(listOf(0, 1), dir, Long.MAX_VALUE, { "app.apk" },
                { if (it == 1) cancelled = true; "abc".byteInputStream() }, { cancelled }, {})
        }
        assertFalse(dir.exists())
    }
    @Test fun normalizesExactUrisInOrderAndRejectsUnsupportedOrTooManyBeforeOpening() {
        assertEquals(listOf("content://a", "file:///b"), ImportSelection.normalize(listOf("content://a", "file:///b", "content://a")))
        assertEquals(128, ImportSelection.normalize((1..128).map { "content://$it" }).size)
        assertThrows(IllegalArgumentException::class.java) { ImportSelection.normalize((1..129).map { "content://$it" }) }
        assertThrows(IllegalArgumentException::class.java) { ImportSelection.normalize(listOf("content://a", "https://b")) }
        assertThrows(IllegalArgumentException::class.java) { ImportSelection.normalize(emptyList()) }
        assertNull(ImportSelection.knownTotal(listOf(3L, null)))
        assertNull(ImportSelection.knownTotal(listOf(Long.MAX_VALUE, 1L)))
        assertEquals(7L, ImportSelection.knownTotal(listOf(3L, 4L)))
    }
}
