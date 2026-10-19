// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SecurityAndTargetingTest {
    private val device = DeviceProfile(36, listOf("arm64-v8a", "armeabi-v7a"), 420, listOf("ru-RU", "en-US"))
    private fun workspace(block: (File) -> Unit) {
        val root = Files.createTempDirectory("splitbox-security").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun reject(block: () -> Unit) {
        try { block(); fail("Ожидалось исключение PreparationException") } catch (_: PreparationException) { }
    }
    private fun prepare(root: File, source: File, auto: Boolean = true, cancel: () -> Boolean = { false }): PreparedPackage =
        ArchivePreparer().prepare(source, source.name, File(root, "stage").apply { mkdir() }, device, PrepareOptions(autoSelect = auto), isCancelled = cancel)
    @Test fun rejectsDuplicateZipEntryNames() = workspace { root ->
        val file = CoreFixtures.zip(File(root, "duplicate.zip"), listOf("a.apk" to byteArrayOf(1), "b.apk" to byteArrayOf(2)))
        val bytes = file.readBytes()
        for (i in 0..bytes.size - 5) if (bytes.copyOfRange(i, i + 5).contentEquals("b.apk".toByteArray())) bytes[i] = 'a'.code.toByte()
        file.writeBytes(bytes)
        reject { prepare(root, file) }
    }
    @Test fun rejectsZipFileDirectoryCollision() = workspace { root ->
        val zip = CoreFixtures.zip(File(root, "collision.zip"), listOf("a" to byteArrayOf(1), "a/base.apk" to CoreFixtures.bytesApk()))
        reject { prepare(root, zip) }
    }
    @Test fun cancellationDuringInflationRemovesOutputOnly() = workspace { root ->
        val stage = File(root, "stage").apply { mkdir() }
        val source = CoreFixtures.zip(File(stage, "source.bin"), listOf("payload" to ByteArray(512 * 1024)))
        var observedPartialFile = false
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            ArchivePreparer().prepare(source, "payload.xapk", stage, device, isCancelled = {
                (File(stage, "prepared/payload").length() > 0).also { if (it) observedPartialFile = true }
            })
        }
        assertTrue(source.exists()); assertFalse(File(stage, "prepared").exists()); assertTrue(observedPartialFile)
    }
    @Test fun supportsSourceInsideStagingAndNeverOverwritesExistingResult() = workspace { root ->
        val stage = File(root, "stage").apply { mkdir() }
        val source = CoreFixtures.apk(File(stage, "source.bin"))
        val result = ArchivePreparer().prepare(source, "incoming.apk", stage, device)
        assertEquals("incoming.apk", result.sourceName); assertTrue(source.exists())
        reject { ArchivePreparer().prepare(source, "incoming.apk", stage, device) }
        assertTrue(result.apks.single().file.exists())
    }
    @Test fun refusesSymlinkStaging() = workspace { root ->
        val target = File(root, "target").apply { mkdir() }; val link = File(root, "stage")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        val source = CoreFixtures.apk(File(root, "base.apk"))
        reject { ArchivePreparer().prepare(source, source.name, link, device) }
        assertTrue(target.listFiles().orEmpty().isEmpty())
        Files.delete(link.toPath())
    }
    @Test fun rejectsUnknownConfigurationTargetWithoutToc() = workspace { root ->
        val zip = CoreFixtures.zip(File(root, "unknown.apkm"), listOf("base.apk" to CoreFixtures.bytesApk(),
            "texture.apk" to CoreFixtures.bytesApk(CoreFixtures.manifest(split = "config.astc"))))
        reject { prepare(root, zip) }
    }
    @Test fun manualModeKeepsCompatibleDensityChoicesButLocksIncompatibleAbi() = workspace { root ->
        val zip = CoreFixtures.zip(File(root, "manual.xapk"), listOf("base.apk" to CoreFixtures.bytesApk(),
            "hdpi.apk" to CoreFixtures.bytesApk(CoreFixtures.manifest(split = "config.hdpi")),
            "xxhdpi.apk" to CoreFixtures.bytesApk(CoreFixtures.manifest(split = "config.xxhdpi")),
            "x86.apk" to CoreFixtures.bytesApk(CoreFixtures.manifest(split = "config.x86")),
            "arm.apk" to CoreFixtures.bytesApk(CoreFixtures.manifest(split = "config.arm64_v8a"))))
        val apks = prepare(root, zip, auto = false).apks
        assertEquals(4, apks.count { it.selected }); assertTrue(apks.single { it.metadata.splitName == "config.x86" }.locked)
    }
    @Test fun longVersionCodePreservesUnsignedLowWord() {
        val m = BinaryManifestReader().read(CoreFixtures.manifest(version = -1, versionMajor = 2))
        assertEquals((2L shl 32) or 0xffffffffL, m.versionCode)
    }
    @Test fun legacyIsSplitRequiredRejectsSingleBase() = workspace { root ->
        reject { prepare(root, CoreFixtures.apk(File(root, "incomplete.apk"), CoreFixtures.manifest(legacyRequired = true))) }
    }
    @Test fun configForSplitNeedsExistingFeatureAndSelectedMaster() {
        val m = ApkMetadata("dev.test", 1, "", 23, 36)
        val base = ApkEntry(File("base"), "base", m, 1)
        val config = ApkEntry(File("config"), "config", m.copy(splitName = "camera.config.ru", configForSplit = "camera"), 1)
        reject { validateSelection(listOf(base, config)) }
        val feature = ApkEntry(File("feature"), "feature", m.copy(splitName = "camera", isFeatureSplit = true), 1, selected = false)
        reject { validateSelection(listOf(base, config, feature)) }
        validateSelection(listOf(base, config, feature.copy(selected = true)))
    }
    @Test fun requiredTypesAreExactStringsAndApplyToConfigurationApks() {
        val m = ApkMetadata("dev.test", 1, "", 23, 36, requiredSplitTypes = setOf("base__abi", "base__density"))
        val base = ApkEntry(File("base"), "base", m, 1)
        val abi = ApkEntry(File("abi"), "abi", m.copy(splitName = "config.arm64_v8a", requiredSplitTypes = setOf("shared"), splitTypes = setOf("base__abi")), 1)
        val dpi = ApkEntry(File("dpi"), "dpi", m.copy(splitName = "config.xxhdpi", requiredSplitTypes = emptySet(), splitTypes = setOf("base__density", "shared")), 1)
        validateSelection(listOf(base, abi, dpi))
        reject { validateSelection(listOf(base, abi, dpi.copy(selected = false))) }
        reject { validateSelection(listOf(base, abi, dpi.copy(metadata = dpi.metadata.copy(splitTypes = setOf("base__density"))))) }
    }
    @Test fun rejectsCyclesAndMixedNativeAbiSelection() {
        val m = ApkMetadata("dev.test", 1, "", 23, 36)
        val base = ApkEntry(File("base"), "base", m, 1)
        val a = ApkEntry(File("a"), "a", m.copy(splitName = "a", isFeatureSplit = true, usesSplits = listOf("b")), 1)
        val b = ApkEntry(File("b"), "b", m.copy(splitName = "b", isFeatureSplit = true, usesSplits = listOf("a")), 1)
        reject { validateSelection(listOf(base, a, b)) }
        reject { validateSelection(listOf(base.copy(nativeAbis = setOf("arm64-v8a")), a.copy(metadata = a.metadata.copy(usesSplits = emptyList()), nativeAbis = setOf("armeabi-v7a")))) }
    }
    @Test fun bundleTargetingUsesAbiAlternativesSdkIntervalsDensityAndLanguage() {
        fun abi(alias: Int) = CoreFixtures.scalar(1, alias.toLong())
        val bestAbi = CoreFixtures.pb(1 to abi(3), 2 to abi(2))
        val fallbackAbi = CoreFixtures.pb(1 to abi(2), 2 to abi(3))
        fun density(alias: Int) = CoreFixtures.scalar(1, alias.toLong())
        val dpi = CoreFixtures.pb(1 to density(7), 2 to density(5))
        val language = CoreFixtures.pb(1 to CoreFixtures.text("ru"), 2 to CoreFixtures.text("de"))
        val apkTarget = CoreFixtures.pb(1 to bestAbi, 4 to dpi, 3 to language)
        val badTarget = CoreFixtures.pb(1 to fallbackAbi)
        val toc = CoreFixtures.toc(CoreFixtures.variant(CoreFixtures.variantTargetSdk(34), CoreFixtures.apkSet("base",
            CoreFixtures.apkDescription("base.apk", master = true),
            CoreFixtures.apkDescription("chosen.apk", apkTarget, splitId = "config.arm64_v8a"),
            CoreFixtures.apkDescription("other.apk", badTarget, splitId = "config.armeabi_v7a"))))
        assertEquals(setOf("base.apk", "chosen.apk"), BundleTocReader().select(toc, device).apks.keys)
    }
    @Test fun bundleVariantsRespectVariantNumberPriorityAndNeverMerge() {
        fun variant(number: Int, path: String) = CoreFixtures.variant(CoreFixtures.variantTargetSdk(23),
            CoreFixtures.apkSet("base", CoreFixtures.apkDescription(path, master = true))) + CoreFixtures.scalar(3, number.toLong())
        val toc = CoreFixtures.toc(variant(1, "one.apk"), variant(8, "eight.apk"))
        assertEquals(setOf("eight.apk"), BundleTocReader().select(toc, device).apks.keys)
    }
    @Test fun bundleSdkAlternativesExcludeLowerSdkVariantAndNoMatchRejects() {
        fun sdk(min: Int) = CoreFixtures.pb(1 to CoreFixtures.scalar(1, min.toLong()))
        val lower = CoreFixtures.pb(1 to CoreFixtures.pb(1 to sdk(23), 2 to sdk(34)))
        val upper = CoreFixtures.pb(1 to CoreFixtures.pb(1 to sdk(34), 2 to sdk(23)))
        val toc = CoreFixtures.toc(CoreFixtures.variant(lower, CoreFixtures.apkSet("base", CoreFixtures.apkDescription("lower.apk", master = true))),
            CoreFixtures.variant(upper, CoreFixtures.apkSet("base", CoreFixtures.apkDescription("upper.apk", master = true))))
        assertEquals(setOf("upper.apk"), BundleTocReader().select(toc, device).apks.keys)
        reject { BundleTocReader().select(toc, device.copy(sdk = 20)) }
    }
    @Test fun bundleWarnsForAssetPacksAndRejectsMissingModuleDependency() {
        val base = CoreFixtures.variant(byteArrayOf(), CoreFixtures.apkSet("base", CoreFixtures.apkDescription("base.apk", master = true)))
        val withAsset = CoreFixtures.toc(base) + CoreFixtures.pb(3 to CoreFixtures.pb(1 to CoreFixtures.pb(1 to CoreFixtures.text("assets"))))
        assertTrue(BundleTocReader().select(withAsset, device).warnings.any { it.contains("Пакеты ресурсов") })
        val withMissing = CoreFixtures.toc(CoreFixtures.variant(byteArrayOf(), CoreFixtures.apkSet("base", CoreFixtures.apkDescription("base.apk", master = true)),
            CoreFixtures.apkSet("camera", CoreFixtures.apkDescription("camera.apk", master = true, splitId = "camera"), dependencies = listOf("missing"))))
        reject { BundleTocReader().select(withMissing, device) }
    }
    @Test fun bundleRejectsMalformedVarintAndUnknownAbi() {
        reject { BundleTocReader().select(ByteArray(12) { 0xff.toByte() }, device) }
        val unknown = CoreFixtures.toc(CoreFixtures.variant(CoreFixtures.pb(2 to CoreFixtures.abi(99)),
            CoreFixtures.apkSet("base", CoreFixtures.apkDescription("base.apk", master = true))))
        reject { BundleTocReader().select(unknown, device) }
    }
    @Test fun multiAbiPrefersCompatibleSupersetAsBundletoolDoes() {
        val single = setOf("arm64-v8a")
        val combined = setOf("arm64-v8a", "armeabi-v7a")
        assertFalse(Targeting(multiAbi = listOf(single) to listOf(combined)).matches(device))
        assertTrue(Targeting(multiAbi = listOf(combined) to listOf(single)).matches(device))
    }
    @Test fun densitySelectionTreatsNodpiAsNumericAndAnydpiAsUniversal() {
        assertEquals(320, chooseDensity(listOf(320, 65535), 420))
        assertEquals(65534, chooseDensity(listOf(320, 65534, 65535), 420))
    }
    @Test fun zip64MarkerWithoutLocatorRejectedBeforeZipFileAllocatesCentralDirectory() = workspace { root ->
        val file = CoreFixtures.zip(File(root, "zip64.zip"), listOf("base.apk" to CoreFixtures.bytesApk()))
        val bytes = file.readBytes()
        bytes[bytes.size - 14] = 0xff.toByte(); bytes[bytes.size - 13] = 0xff.toByte()
        bytes[bytes.size - 12] = 0xff.toByte(); bytes[bytes.size - 11] = 0xff.toByte()
        file.writeBytes(bytes)
        try { prepare(root, file); fail("Неполный ZIP64 должен быть отклонён") } catch (e: PreparationException) {
            assertTrue(e.message.orEmpty().contains("ZIP64"))
        }
    }

    @Test fun languageFallbackServesDeviceLanguagesNotCoveredByAlternatives() {
        assertTrue(Targeting(language = emptyList<String>() to listOf("ru")).matches(device))
        assertFalse(Targeting(language = emptyList<String>() to listOf("ru", "en")).matches(device))
    }
    @Test fun rejectsDecodedStringPoolAggregateAmplification() {
        val count = 4097
        val text = "a".repeat(1024).toByteArray(Charsets.UTF_16LE)
        val poolSize = 28 + count * 4 + text.size + 4
        val bytes = java.nio.ByteBuffer.allocate(8 + poolSize).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            putShort(3); putShort(8); putInt(8 + poolSize)
            putShort(1); putShort(28); putInt(poolSize); putInt(count); putInt(0); putInt(0)
            putInt(28 + count * 4); putInt(0)
            repeat(count) { putInt(0) }
            putShort(1024); put(text); putShort(0)
        }.array()
        try { BinaryManifestReader().read(bytes); fail("Суммарный объём декодированных строк должен быть ограничен") } catch (e: PreparationException) {
            assertTrue(e.message.orEmpty().contains("Суммарный"))
        }
    }
    @Test fun finalSelectionCannotRemoveSoleAbiOnOldOrLegacyManifest() {
        val metadata = ApkMetadata("dev.test", 1, "", 23, 36)
        val base = ApkEntry(File("base"), "base", metadata, 1, locked = true)
        val abi = ApkEntry(File("abi"), "abi", metadata.copy(splitName = "config.arm64_v8a"), 1, nativeAbis = setOf("arm64-v8a"), selected = false)
        val lang = ApkEntry(File("ru"), "ru", metadata.copy(splitName = "config.ru"), 1)
        reject { validateSelection(listOf(base, abi, lang)) }
        reject { validateSelection(listOf(base.copy(metadata = metadata.copy(requiredSplitTypes = setOf(LEGACY_ANY_SPLIT))), abi, lang)) }
    }

    @Test fun manualModeCanKeepAlternativeCompatibleNativeAbiConfigs() = workspace { root ->
        val zip = CoreFixtures.zip(File(root, "multiabi.apkm"), listOf("base.apk" to CoreFixtures.bytesApk(),
            "arm64.apk" to CoreFixtures.bytesApk(CoreFixtures.manifest(split = "config.arm64_v8a"), "arm64-v8a"),
            "arm32.apk" to CoreFixtures.bytesApk(CoreFixtures.manifest(split = "config.armeabi_v7a"), "armeabi-v7a")))
        assertEquals(3, prepare(root, zip, auto = false).apks.count { it.selected })
    }

    @Test fun bundleCannotSilentlyDropAllIncompatibleAbiConfigsFromModule() {
        val toc = CoreFixtures.toc(CoreFixtures.variant(byteArrayOf(), CoreFixtures.apkSet("base",
            CoreFixtures.apkDescription("base.apk", master = true),
            CoreFixtures.apkDescription("x86.apk", CoreFixtures.apkTargetAbi(4), splitId = "config.x86"))))
        reject { BundleTocReader().select(toc, device) }
    }

}
