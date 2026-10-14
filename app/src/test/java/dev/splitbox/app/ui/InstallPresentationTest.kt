// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.ui

import dev.splitbox.app.model.OperationPhase
import dev.splitbox.app.model.UiState
import dev.splitbox.core.ApkEntry
import dev.splitbox.core.ApkMetadata
import dev.splitbox.core.PreparedPackage
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class InstallPresentationTest {
    private fun entry(name: String?, locked: Boolean = false, feature: Boolean = false, abis: Set<String> = emptySet()) = ApkEntry(
        File("unused"), name ?: "base.apk",
        ApkMetadata("dev.example", 1, "1", 34, 36, splitName = name, isFeatureSplit = feature),
        10, nativeAbis = abis, locked = locked,
    )

    @Test fun groupsRetainEveryEntryInReadableOrder() {
        val entries = listOf(entry("config.extra_resources"), entry("maps.config.ru"), entry("config.480dpi"),
            entry("config.arm64_v8a"), entry("maps", feature = true), entry(null), entry("config.custom_payload", abis = setOf("x86")))
        val groups = groupSplits(entries, emptySet(), selectedOnly = false)
        assertEquals(listOf(SplitGroupKind.BASE, SplitGroupKind.FEATURE, SplitGroupKind.ABI,
            SplitGroupKind.DENSITY, SplitGroupKind.LANGUAGE, SplitGroupKind.OTHER), groups.map { it.kind })
        assertEquals(listOf("config.arm64_v8a", "config.custom_payload"), groups[2].entries.map { it.archivePath })
        assertEquals(7, groups.sumOf { it.entries.size })
    }

    @Test fun selectedFilterKeepsLockedEntriesEvenWhenUnselectedAndDoesNotInventSelection() {
        val base = entry(null, locked = true)
        val incompatible = entry("config.x86", locked = true)
        val selected = entry("config.ru")
        val optional = entry("config.en")
        val groups = groupSplits(listOf(base, incompatible, selected, optional), setOf(selected.archivePath), selectedOnly = true)
        assertEquals(listOf("base.apk", "config.x86", "config.ru"), groups.flatMap { it.entries }.map { it.archivePath })
    }

    @Test fun namesHandleAbiAliasesAndRegionalLanguagesWithoutMisclassifyingFeature() {
        val entries = listOf(entry("config.armeabi_v7a"), entry("config.x86_64"), entry("config.xxhdpi"),
            entry("config.pt_rBR"), entry("config.zh-Hant-TW"), entry("config.en", feature = true))
        val groups = groupSplits(entries, emptySet(), false)
        assertEquals(listOf(1, 2, 1, 2), groups.map { it.entries.size })
        assertEquals(listOf(SplitGroupKind.FEATURE, SplitGroupKind.ABI, SplitGroupKind.DENSITY, SplitGroupKind.LANGUAGE), groups.map { it.kind })
    }

    @Test fun installationRequiresDurableCurrentValidNonemptySelection() {
        val base = entry(null, locked = true)
        val prepared = PreparedPackage("test.apk", base.metadata, listOf(base), emptyList(), emptyList(), File("unused"))
        val ready = UiState(phase = OperationPhase.READY, prepared = prepared, selectedPaths = setOf(base.archivePath))
        assertTrue(canInstallSelection(ready))
        listOf(ready.copy(savingSelection = true), ready.copy(selectionSaveError = "Нет места"),
            ready.copy(selectionValidationPending = true), ready.copy(selectionError = "Нет ABI"),
            ready.copy(selectedPaths = emptySet()), ready.copy(prepared = null),
            ready.copy(phase = OperationPhase.INSTALLING)).forEach { assertFalse(canInstallSelection(it)) }
    }

    @Test fun byteProgressSupportsUnknownZeroAndOutOfRangeTotals() {
        assertNull(copyProgressFraction(100, null))
        assertNull(copyProgressFraction(100, 0))
        assertNull(copyProgressFraction(100, -1))
        assertEquals(.5f, copyProgressFraction(50, 100)!!, 0f)
        assertEquals(1f, copyProgressFraction(Long.MAX_VALUE, 10)!!, 0f)
        assertEquals(0f, copyProgressFraction(-1, 10)!!, 0f)
    }
}
