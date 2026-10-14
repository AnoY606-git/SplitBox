// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CacheSizeIndexTest {
    private fun workspace(block: (File) -> Unit) {
        val root = Files.createTempDirectory("cache-index").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun data(root: File, name: String, size: Int): File = File(root, name).apply {
        mkdirs(); File(this, "payload").writeBytes(ByteArray(size))
    }
    @Test fun readsMemorySnapshotUntilExplicitScanOrDirectoryRefresh() = workspace { root ->
        val a = data(root, "a", 3)
        data(root, "b", 5)
        val index = CacheSizeIndex(root)
        assertEquals(0L, index.bytes())
        assertEquals(8L, index.rescan())
        File(a, "payload").writeBytes(ByteArray(7))
        assertEquals(8L, index.bytes())
        assertEquals(12L, index.refreshDirectory(a))
        assertEquals(12L, index.bytes())
    }
    @Test fun refreshingOneDirectoryDoesNotScanOtherDirectories() = workspace { root ->
        val a = data(root, "a", 3)
        val b = data(root, "b", 5)
        val scanned = mutableListOf<String>()
        val index = CacheSizeIndex(root) { directory ->
            scanned += directory.name
            directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        index.rescan(); scanned.clear()
        File(b, "payload").writeBytes(ByteArray(9))
        File(a, "payload").writeBytes(ByteArray(4))
        assertEquals(9L, index.refreshDirectory(a))
        assertEquals(listOf("a"), scanned)
        assertEquals(13L, index.rescan())
    }
    @Test fun refreshMissingDirectoryRemovesItsPreviousSize() = workspace { root ->
        val a = data(root, "a", 3)
        val index = CacheSizeIndex(root)
        index.rescan(); a.deleteRecursively()
        assertEquals(0L, index.refreshDirectory(a))
    }
    @Test fun deletionAndRefreshCannotBeOverwrittenByAnOlderScan() = workspace { root ->
        val a = data(root, "a", 3)
        val b = data(root, "b", 5)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var pause = false
        val index = CacheSizeIndex(root) { directory ->
            val size = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            if (pause && directory == a) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            size
        }
        index.rescan(); pause = true
        val pool = Executors.newFixedThreadPool(3)
        try {
            val scan = pool.submit<Long> { index.rescan() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // Даже во время медленного обхода чтение total не выполняет IO и не ждёт lock.
            assertEquals(8L, pool.submit<Long> { index.bytes() }.get(1, TimeUnit.SECONDS))
            File(b, "payload").writeBytes(ByteArray(9))
            val refresh = pool.submit<Long> { index.refreshDirectory(b) }
            val deletion = pool.submit { index.delete(a) }
            release.countDown()
            scan.get(5, TimeUnit.SECONDS); refresh.get(5, TimeUnit.SECONDS); deletion.get(5, TimeUnit.SECONDS)
            assertFalse(a.exists())
            assertEquals(9L, index.bytes())
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @Test fun failedRescanKeepsLastCompleteSnapshot() = workspace { root ->
        val a = data(root, "a", 3)
        data(root, "b", 5)
        var failScan = false
        val index = CacheSizeIndex(root) { directory ->
            if (failScan && directory.name == "b") throw java.io.IOException("Чтение недоступно")
            directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        index.rescan()
        File(a, "payload").writeBytes(ByteArray(9)); failScan = true
        assertThrows(java.io.IOException::class.java) { index.rescan() }
        assertEquals(8L, index.bytes())
    }
    @Test fun rejectsDirectoryOutsideCacheAndDoesNotFollowSymlinks() = workspace { root ->
        val outside = Files.createTempDirectory("outside-cache").toFile()
        try {
            File(outside, "payload").writeBytes(ByteArray(100))
            val index = CacheSizeIndex(root)
            assertThrows(IllegalStateException::class.java) { index.refreshDirectory(outside) }
            assertThrows(IllegalStateException::class.java) { index.delete(outside) }
            Files.createSymbolicLink(File(root, "linked").toPath(), outside.toPath())
            assertEquals(0L, index.rescan())
            assertTrue(outside.isDirectory)
        } finally { outside.deleteRecursively() }
    }
}
