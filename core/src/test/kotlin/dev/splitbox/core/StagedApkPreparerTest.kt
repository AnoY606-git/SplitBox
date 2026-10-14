// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class StagedApkPreparerTest {
    private val device = DeviceProfile(34, listOf("arm64-v8a"), 320, listOf("ru"))
    private fun fixture(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("staged-apks").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
    private fun apk(dir: File, name: String, manifest: ByteArray = CoreFixtures.manifest()) =
        StagedApk(CoreFixtures.apk(File(dir, name), manifest), "${name}.apk")
    private fun prepare(dir: File, entries: List<StagedApk>, limit: Long = Long.MAX_VALUE,
        cancelled: () -> Boolean = { false }) = StagedApkPreparer().prepare(entries, dir, device,
        PrepareOptions(limit), isCancelled = cancelled)

    @Test fun preparesOwnedApksInPlaceAndUsesExistingSelectionRules() = fixture { dir ->
        val base = apk(dir, "0000.bin")
        val ru = apk(dir, "0001.bin", CoreFixtures.manifest(split = "config.ru"))
        val en = apk(dir, "0002.bin", CoreFixtures.manifest(split = "config.en"))
        val result = prepare(dir, listOf(base, ru, en))
        assertEquals("Набор из 3 APK", result.sourceName)
        assertEquals(listOf(base.file, ru.file, en.file), result.apks.map { it.file })
        assertEquals(listOf(true, true, false), result.apks.map { it.selected })
        assertTrue(result.obbFiles.isEmpty())
        assertEquals(3, dir.listFiles()!!.size)
    }
    @Test fun rejectsMixedPackageAndVersion() = fixture { dir ->
        val base = apk(dir, "base")
        listOf(CoreFixtures.manifest(packageName = "dev.other", split = "config.ru"),
            CoreFixtures.manifest(version = 8, split = "config.ru")).forEachIndexed { i, manifest ->
            assertThrows(PreparationException::class.java) { prepare(dir, listOf(base, apk(dir, "bad$i", manifest))) }
        }
    }
    @Test fun rejectsMissingBaseAndDuplicateSplit() = fixture { dir ->
        val a = apk(dir, "a", CoreFixtures.manifest(split = "config.ru"))
        val b = apk(dir, "b", CoreFixtures.manifest(split = "config.ru"))
        assertThrows(PreparationException::class.java) { prepare(dir, listOf(a)) }
        assertThrows(PreparationException::class.java) { prepare(dir, listOf(apk(dir, "base"), a, b)) }
    }
    @Test fun rejectsArchiveDisguisedAsApkWithoutExtractingIt() = fixture { dir ->
        val archive = CoreFixtures.zip(File(dir, "fake.apk"), listOf("base.apk" to CoreFixtures.bytesApk()))
        val error = assertThrows(PreparationException::class.java) { prepare(dir, listOf(StagedApk(archive, "fake.apk"))) }
        assertTrue(error.message!!.contains("AndroidManifest.xml"))
        assertEquals(1, dir.listFiles()!!.size)
    }
    @Test fun rejectsOutsideSymlinkAndDuplicateFiles() = fixture { dir ->
        val base = apk(dir, "base")
        assertThrows(PreparationException::class.java) { prepare(dir, listOf(base, base.copy(archivePath = "other.apk"))) }
        fixture { outside ->
            val external = apk(outside, "external")
            assertThrows(PreparationException::class.java) { prepare(dir, listOf(external)) }
            val link = File(dir, "link")
            Files.createSymbolicLink(link.toPath(), external.file.toPath())
            assertThrows(PreparationException::class.java) { prepare(dir, listOf(StagedApk(link, "link.apk"))) }
            val subdir = File(dir, "linked"); Files.createSymbolicLink(subdir.toPath(), outside.toPath())
            assertThrows(PreparationException::class.java) { prepare(dir, listOf(StagedApk(File(subdir, "external"), "link.apk"))) }
        }
    }
    @Test fun rejectsSymlinkHiddenByParentTraversal() = fixture { dir ->
        val base = apk(dir, "base")
        val inner = File(dir, "inner").apply { mkdir() }
        Files.createSymbolicLink(File(dir, "link").toPath(), inner.toPath())
        val disguised = base.copy(file = File(dir, "link/../base"))
        assertThrows(PreparationException::class.java) { prepare(dir, listOf(disguised)) }
    }
    @Test fun enforcesTotalSizeAndCancellation() = fixture { dir ->
        val entries = listOf(apk(dir, "base"), apk(dir, "ru", CoreFixtures.manifest(split = "config.ru")))
        val bytes = entries.sumOf { it.file.length() }
        assertEquals(2, prepare(dir, entries, bytes).apks.size)
        assertThrows(PreparationException::class.java) { prepare(dir, entries, bytes - 1) }
        assertThrows(CancellationException::class.java) { prepare(dir, entries, cancelled = { true }) }
    }
}
