// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CommonAbiSelectionTest {
    private val arm64 = "arm64-v8a"
    private val arm32 = "armeabi-v7a"
    private val device = DeviceProfile(36, listOf(arm64, arm32), 420, listOf("ru-RU"))
    private fun apk(split: String? = null, owner: String? = null, feature: Boolean = false,
        abis: Set<String> = emptySet()) = ApkEntry(File("unused"), split ?: "base",
        ApkMetadata("dev.test", 7, "1.7", 23, 36, split, owner, feature), 1, abis)
    private fun config(abi: String, module: String = "base") = apk(
        (if (module == "base") "" else "$module.") + "config." + abi.replace('-', '_'),
        if (module == "base") null else module, abis = setOf(abi))
    private fun selected(entries: List<ApkEntry>, auto: Boolean = true, toc: Boolean = false) =
        SplitSelector().select(entries, device, auto, toc).filter { it.selected }.map { it.archivePath }.toSet()
    private fun reject(entries: List<ApkEntry>, auto: Boolean = true, toc: Boolean = false) {
        try { selected(entries, auto, toc); fail("Ожидалось отклонение несовместимого набора ABI") }
        catch (_: PreparationException) { }
    }
    @Test fun automaticSelectionUsesCommonAbiAcrossModules() {
        val entries = listOf(apk(), config(arm64), config(arm32), apk("camera", feature = true), config(arm32, "camera"))
        assertEquals(setOf("base", "config.armeabi_v7a", "camera", "camera.config.armeabi_v7a"), selected(entries))
    }
    @Test fun baseNativeLibrariesConstrainConfigurationAbi() {
        assertEquals(setOf("base", "config.armeabi_v7a"), selected(listOf(apk(abis = setOf(arm32)), config(arm64), config(arm32))))
    }
    @Test fun featureNativeLibrariesConstrainConfigurationAbiWithoutOwnConfigs() {
        val entries = listOf(apk(), config(arm64), config(arm32), apk("camera", feature = true, abis = setOf(arm32)))
        assertEquals(setOf("base", "config.armeabi_v7a", "camera"), selected(entries))
    }
    @Test fun commonAbiStillFollowsDevicePreference() {
        val entries = listOf(apk(), config(arm32), config(arm64), apk("camera", feature = true), config(arm32, "camera"), config(arm64, "camera"))
        assertEquals(setOf("base", "config.arm64_v8a", "camera", "camera.config.arm64_v8a"), selected(entries))
    }
    @Test fun incompatibleConfigurationSdkDoesNotContributeCommonAbi() {
        val tooNew = config(arm64, "camera").let { it.copy(metadata = it.metadata.copy(minSdk = 37)) }
        val entries = listOf(apk(), config(arm64), config(arm32), apk("camera", feature = true), tooNew, config(arm32, "camera"))
        assertEquals(setOf("base", "config.armeabi_v7a", "camera", "camera.config.armeabi_v7a"), selected(entries))
    }
    @Test fun disjointModuleAbisRemainRejected() {
        reject(listOf(apk(), config(arm64), apk("camera", feature = true), config(arm32, "camera")))
        reject(listOf(apk(abis = setOf(arm64)), apk("camera", feature = true, abis = setOf(arm32))))
    }
    @Test fun manualSelectionKeepsBothCompatibleAlternatives() {
        val entries = listOf(apk(), config(arm64), config(arm32), apk("camera", feature = true), config(arm32, "camera"))
        assertEquals(setOf("base", "config.arm64_v8a", "config.armeabi_v7a", "camera", "camera.config.armeabi_v7a"), selected(entries, auto = false))
    }
    @Test fun tocSelectionKeepsItsCompatibleAlternatives() {
        val entries = listOf(apk(), config(arm64), config(arm32), apk("camera", feature = true), config(arm32, "camera"))
        assertEquals(setOf("base", "config.arm64_v8a", "config.armeabi_v7a", "camera", "camera.config.armeabi_v7a"), selected(entries, toc = true))
    }
    @Test fun modulesWithoutNativeLibrariesDoNotConstrainAbi() {
        assertEquals(setOf("base", "camera", "config.ru"), selected(listOf(apk(), apk("camera", feature = true), apk("config.ru"))))
        assertEquals(setOf("base", "camera", "config.arm64_v8a"), selected(listOf(apk(), apk("camera", feature = true), config(arm64), config(arm32))))
    }
    @Test fun genericTocConfigurationDoesNotRequireAbiTarget() {
        assertEquals(setOf("base", "config.texture_astc"), selected(listOf(apk(), apk("config.texture_astc")), toc = true))
        reject(listOf(apk(), apk("config.texture_astc")))
    }
}
