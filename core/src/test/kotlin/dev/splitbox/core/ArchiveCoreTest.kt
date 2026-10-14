// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ArchiveCoreTest {
    private val device = DeviceProfile(36, listOf("arm64-v8a", "armeabi-v7a"), 420, listOf("ru-RU", "en-US"))
    private fun workspace(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("splitbox-test").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
    private fun prepare(dir: File, source: File, limit: Long = 2L * 1024 * 1024 * 1024,
        cancel: () -> Boolean = { false }, auto: Boolean = true): PreparedPackage =
        ArchivePreparer().prepare(source, source.name, File(dir, "stage").apply { mkdir() }, device,
            PrepareOptions(limit, auto), isCancelled = cancel)
    private fun rejected(message: String? = null, block: () -> Unit) {
        try { block(); fail("Ожидалось исключение PreparationException") } catch (e: PreparationException) {
            if (message != null) assertTrue("Сообщение «${e.message}» должно содержать «$message»", e.message.orEmpty().contains(message, true))
        }
    }
    @Test fun rejectsTraversalAndAbsoluteNamesWithoutWritingOutsideStage() = workspace { dir ->
        listOf("../escaped.apk", "/escaped.apk", "a/../../escaped.apk", "a\\..\\escaped.apk", "C:/escaped.apk").forEachIndexed { i, name ->
            val zip = CoreFixtures.zip(File(dir, "bad$i.zip"), listOf(name to byteArrayOf(1)))
            rejected { prepare(dir, zip) }; assertFalse(File(dir, "escaped.apk").exists())
        }
    }
    @Test fun enforcesActualInflatedByteLimitAndCleansPartialFiles() = workspace { dir ->
        val zip = CoreFixtures.zip(File(dir, "bomb.zip"), listOf("padding.bin" to ByteArray(64 * 1024)))
        rejected("лимит") { prepare(dir, zip, 1024) }
        assertTrue(!File(dir, "stage").exists() || File(dir, "stage").listFiles().orEmpty().isEmpty())
        assertTrue(zip.exists())
    }
    @Test fun rejectsEmptyAndCorruptArchives() = workspace { dir ->
        rejected { prepare(dir, CoreFixtures.zip(File(dir, "empty.zip"), emptyList())) }
        rejected { prepare(dir, File(dir, "broken.apks").apply { writeBytes(byteArrayOf(80, 75, 3, 4, 1)) }) }
    }
    @Test fun cancellationStopsStreamingAndPreservesInput() = workspace { dir ->
        val zip = CoreFixtures.zip(File(dir, "cancel.zip"), listOf("base.apk" to CoreFixtures.bytesApk()))
        rejected("отмен") { prepare(dir, zip, cancel = { true }) }; assertTrue(zip.exists())
    }
    @Test fun readsBinaryManifestWithSplitDependenciesAndLongVersion() {
        val metadata = BinaryManifestReader().read(CoreFixtures.manifest(split = "camera", feature = true, uses = listOf("shared"), requiredTypes = "abi,density", splitTypes = "feature"))
        assertEquals("dev.test", metadata.packageName); assertEquals(7L, metadata.versionCode)
        assertEquals("camera", metadata.splitName); assertTrue(metadata.isFeatureSplit)
        assertEquals(listOf("shared"), metadata.usesSplits); assertEquals(setOf("abi", "density"), metadata.requiredSplitTypes)
    }
    @Test fun rejectsTruncatedBinaryManifest() { rejected { BinaryManifestReader().read(CoreFixtures.manifest().copyOf(50)) } }
    @Test fun rawApkIsCopiedLockedAndInspected() = workspace { dir ->
        val source = CoreFixtures.apk(File(dir, "source.apk"), abi = "arm64-v8a")
        val result = prepare(dir, source)
        assertEquals(1, result.apks.size); assertTrue(result.apks.single().selected); assertTrue(result.apks.single().locked)
        assertEquals(setOf("arm64-v8a"), result.apks.single().nativeAbis)
        assertNotEquals(source.canonicalPath, result.apks.single().file.canonicalPath)
        validateSelection(result.apks)
    }
    @Test fun rawOwnedSourceIsReusedWithoutSecondPayloadAndSurvivesCleanup() = workspace { dir ->
        val stage = File(dir, "stage").apply { mkdir() }
        val source = CoreFixtures.apk(File(stage, "source.bin"))
        val original = source.readBytes()
        val result = ArchivePreparer().prepare(source, "source.apk", stage, device)
        assertEquals(source.canonicalFile, result.apks.single().file.canonicalFile)
        assertEquals("base.apk", result.apks.single().archivePath)
        assertEquals(listOf(source.canonicalPath), stage.walkTopDown().filter { it.isFile }.map { it.canonicalPath }.toList())
        File(stage, "prepared").deleteRecursively()
        assertArrayEquals(original, source.readBytes())
    }
    @Test fun ownedRawSourceStillEnforcesLimitAndPreservesRecoveryInput() = workspace { dir ->
        val stage = File(dir, "stage").apply { mkdir() }
        val source = CoreFixtures.apk(File(stage, "source.bin"))
        rejected("лимит") { ArchivePreparer().prepare(source, "source.apk", stage, device, PrepareOptions(source.length() - 1)) }
        assertTrue(source.isFile)
        assertFalse(File(stage, "prepared").exists())
    }
    @Test fun externalRawSourceRemainsIndependentAfterPreparation() = workspace { dir ->
        val source = CoreFixtures.apk(File(dir, "external.apk"))
        val original = source.readBytes()
        val result = prepare(dir, source)
        source.writeBytes(byteArrayOf(1))
        assertArrayEquals(original, result.apks.single().file.readBytes())
    }
    @Test fun symlinkSourceInsideWorkDirectoryIsCopiedIndependently() = workspace { dir ->
        val outside = CoreFixtures.apk(File(dir, "external.apk"))
        val stage = File(dir, "stage").apply { mkdir() }
        val source = File(stage, "source.bin")
        Files.createSymbolicLink(source.toPath(), outside.toPath())
        val result = ArchivePreparer().prepare(source, "source.apk", stage, device)
        assertNotEquals(outside.canonicalFile, result.apks.single().file.canonicalFile)
        assertArrayEquals(outside.readBytes(), result.apks.single().file.readBytes())
    }
    @Test fun rejectsMissingBaseIdentityMismatchAndDuplicateSplits() = workspace { dir ->
        val cases = listOf(
            listOf("feature.apk" to CoreFixtures.bytesApk(CoreFixtures.manifest(split = "f", feature = true))),
            listOf("base.apk" to CoreFixtures.bytesApk(), "other.apk" to CoreFixtures.bytesApk(CoreFixtures.manifest(packageName = "dev.other", split = "config.en"))),
            listOf("base.apk" to CoreFixtures.bytesApk(), "a.apk" to CoreFixtures.bytesApk(CoreFixtures.manifest(split = "config.en")), "b.apk" to CoreFixtures.bytesApk(CoreFixtures.manifest(split = "config.en")))
        )
        cases.forEachIndexed { i, entries -> rejected { prepare(dir, CoreFixtures.zip(File(dir, "bad$i.xapk"), entries)) } }
    }
    @Test fun selectsNativeAbiDensityAndLocalesAndLocksFeature() = workspace { dir ->
        val manifests = listOf("base" to CoreFixtures.manifest(), "camera" to CoreFixtures.manifest(split = "camera", feature = true),
            "config.arm64_v8a" to CoreFixtures.manifest(split = "config.arm64_v8a"),
            "config.x86" to CoreFixtures.manifest(split = "config.x86"), "config.xxhdpi" to CoreFixtures.manifest(split = "config.xxhdpi"),
            "config.hdpi" to CoreFixtures.manifest(split = "config.hdpi"), "config.ru" to CoreFixtures.manifest(split = "config.ru"),
            "config.de" to CoreFixtures.manifest(split = "config.de"))
        val source = CoreFixtures.zip(File(dir, "app.apkm"), manifests.map { (name, m) -> "$name.apk" to CoreFixtures.bytesApk(m) })
        val result = prepare(dir, source)
        assertEquals(setOf("base", "camera", "config.arm64_v8a", "config.xxhdpi", "config.ru"), result.apks.filter { it.selected }.map { it.archivePath.removeSuffix(".apk") }.toSet())
        assertTrue(result.apks.first { it.metadata.splitName == "camera" }.locked)
        rejected { validateSelection(result.apks.map { if (it.metadata.splitName == "camera") it.copy(selected = false) else it }) }
    }
    @Test fun rejectsIncompatibleSdkAndNativeAbi() = workspace { dir ->
        rejected { prepare(dir, CoreFixtures.apk(File(dir, "new.apk"), CoreFixtures.manifest(minSdk = 37))) }
        rejected { prepare(dir, CoreFixtures.apk(File(dir, "x86.apk"), abi = "x86")) }
    }
    @Test fun validatesDependenciesAndRequiredSplitTypesAfterManualToggle() {
        val base = ApkEntry(File("base.apk"), "base.apk", ApkMetadata("dev.test", 1, "", 23, 36, requiredSplitTypes = setOf("abi")), 1, locked = true)
        val abi = ApkEntry(File("abi.apk"), "abi.apk", base.metadata.copy(splitName = "config.arm64_v8a", requiredSplitTypes = emptySet(), splitTypes = setOf("abi")), 1)
        validateSelection(listOf(base, abi))
        rejected { validateSelection(listOf(base, abi.copy(selected = false))) }
        rejected { validateSelection(listOf(base.copy(metadata = base.metadata.copy(usesSplits = listOf("missing"))), abi)) }
    }
    @Test fun selectsOneBundletoolVariantAndOnlyInstallTimeModules() = workspace { dir ->
        val toc = CoreFixtures.toc(
            CoreFixtures.variant(CoreFixtures.variantTargetSdk(23), CoreFixtures.apkSet("base", CoreFixtures.apkDescription("old/base.apk", master = true))),
            CoreFixtures.variant(CoreFixtures.variantTargetSdk(34), CoreFixtures.apkSet("base", CoreFixtures.apkDescription("new/base.apk", master = true)),
                CoreFixtures.apkSet("camera", CoreFixtures.apkDescription("new/camera.apk", master = true, splitId = "camera")),
                CoreFixtures.apkSet("download", CoreFixtures.apkDescription("new/download.apk", master = true, splitId = "download"), delivery = 2)))
        val source = CoreFixtures.zip(File(dir, "bundle.apks"), listOf("toc.pb" to toc,
            "old/base.apk" to CoreFixtures.bytesApk(), "new/base.apk" to CoreFixtures.bytesApk(),
            "new/camera.apk" to CoreFixtures.bytesApk(CoreFixtures.manifest(split = "camera", feature = true)),
            "new/download.apk" to CoreFixtures.bytesApk(CoreFixtures.manifest(split = "download", feature = true))))
        val result = prepare(dir, source)
        assertEquals(setOf("new/base.apk", "new/camera.apk"), result.apks.map { it.archivePath }.toSet())
    }
    @Test fun bundletoolRejectsUnknownRequiredTargeting() = workspace { dir ->
        val toc = CoreFixtures.toc(CoreFixtures.variant(CoreFixtures.pb(99 to CoreFixtures.pb(1 to byteArrayOf(1))),
            CoreFixtures.apkSet("base", CoreFixtures.apkDescription("base.apk", master = true))))
        rejected("условия") { prepare(dir, CoreFixtures.zip(File(dir, "unknown.apks"), listOf("toc.pb" to toc, "base.apk" to CoreFixtures.bytesApk()))) }
    }
    @Test fun obbMustMatchPackageAndHaveNumericExpansionVersion() = workspace { dir ->
        val source = CoreFixtures.zip(File(dir, "app.xapk"), listOf("base.apk" to CoreFixtures.bytesApk(), "Android/obb/dev.test/main.7.dev.test.obb" to byteArrayOf(1)))
        assertEquals(1, prepare(dir, source).obbFiles.size)
        File(dir, "stage").deleteRecursively()
        rejected { prepare(dir, CoreFixtures.zip(File(dir, "bad.xapk"), listOf("base.apk" to CoreFixtures.bytesApk(), "main.7.dev.other.obb" to byteArrayOf(1)))) }
    }
    @Test fun reusedObbExpansionVersionMayDifferFromApkVersion() = workspace { dir ->
        val source = CoreFixtures.zip(File(dir, "reused.xapk"), listOf("base.apk" to CoreFixtures.bytesApk(CoreFixtures.manifest(version = 8)),
            "Android/obb/dev.test/main.7.dev.test.obb" to byteArrayOf(1)))
        val result = prepare(dir, source)
        assertEquals(8L, result.metadata.versionCode)
        assertEquals("main.7.dev.test.obb", result.obbFiles.single().file.name)
    }

}
