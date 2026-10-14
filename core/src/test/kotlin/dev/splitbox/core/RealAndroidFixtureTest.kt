// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Создано Android SDK 36 / bundletool 1.18.3 из собственного тестового GPL-приложения репозитория. */
class RealAndroidFixtureTest {
    private val device = DeviceProfile(34, listOf("x86_64", "x86"), 420, listOf("ru-RU"))
    private fun prepare(resource: String, assertions: (PreparedPackage) -> Unit) {
        val dir = Files.createTempDirectory("real-android-fixture").toFile()
        try {
            val source = File(dir, resource)
            javaClass.getResourceAsStream("/fixtures/$resource")!!.use { input -> source.outputStream().use { input.copyTo(it) } }
            val prepared = ArchivePreparer().prepare(source, resource, File(dir, "stage").apply { mkdir() }, device)
            assertEquals("dev.splitbox.fixture", prepared.metadata.packageName)
            assertEquals(1L, prepared.metadata.versionCode)
            assertEquals("1.0", prepared.metadata.versionName)
            assertEquals(34, prepared.metadata.minSdk)
            assertEquals(36, prepared.metadata.targetSdk)
            validateSelection(prepared.apks)
            assertions(prepared)
        } finally { dir.deleteRecursively() }
    }
    @Test fun actualAaptBinaryManifestInApk() = prepare("application.apk") { assertEquals(1, it.apks.size) }
    @Test fun actualBundletoolTocSelectsDensityAndLocale() = prepare("bundletool.apks") { result ->
        assertEquals(setOf("splits/base-master.apk", "splits/base-ru.apk", "splits/base-xxhdpi.apk"), result.apks.map { it.archivePath }.toSet())
        assertTrue(result.apks.all { it.selected })
        assertTrue(result.apks.single { it.metadata.splitName == null }.locked)
    }
    @Test fun actualFlatApks() = prepare("flat.apks") { assertEquals(1, it.apks.size) }
    @Test fun actualFlatApkm() = prepare("flat.apkm") { assertEquals(1, it.apks.size) }
    @Test fun actualXapkWithObb() = prepare("flat.xapk") { assertEquals("main.1.dev.splitbox.fixture.obb", it.obbFiles.single().file.name) }
}
