// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.ui

import dev.splitbox.app.model.OperationPhase
import dev.splitbox.app.model.UiState
import dev.splitbox.core.ApkEntry

internal enum class SplitGroupKind(val title: String) {
    BASE("Базовый APK"), FEATURE("Модули приложения"), ABI("Архитектуры процессора"),
    DENSITY("Плотность экрана"), LANGUAGE("Языки"), OTHER("Другие части"),
}
internal data class SplitGroup(val kind: SplitGroupKind, val entries: List<ApkEntry>)
/** Только представление: классификация не меняет выбор и не заменяет проверку ядра. */
internal fun groupSplits(entries: List<ApkEntry>, selectedPaths: Set<String>, selectedOnly: Boolean, query: String = ""): List<SplitGroup> {
    val term = query.trim()
    val visible = entries.filter { entry ->
        (!selectedOnly || entry.locked || entry.archivePath in selectedPaths) &&
            (term.isEmpty() || entry.archivePath.contains(term, ignoreCase = true) ||
                entry.metadata.splitName.orEmpty().contains(term, ignoreCase = true) ||
                splitGroupKind(entry).title.contains(term, ignoreCase = true))
    }
    val groups = visible.groupBy(::splitGroupKind)
    return SplitGroupKind.entries.mapNotNull { kind -> groups[kind]?.let { SplitGroup(kind, it) } }
}

private val abiNames = setOf("armeabi", "armeabi_v7a", "armeabi-v7a", "arm64_v8a", "arm64-v8a", "x86", "x86_64", "mips", "mips64", "riscv64")
private val densityNames = setOf("ldpi", "mdpi", "tvdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi", "nodpi")
private val numericDensity = Regex("[0-9]{2,5}dpi")
private val languageName = Regex("[a-z]{2,3}([_-]r?[A-Za-z]{2,4})?([_-][A-Za-z0-9]{2,8})?")

private fun splitGroupKind(entry: ApkEntry): SplitGroupKind {
    val name = entry.metadata.splitName ?: return SplitGroupKind.BASE
    if (entry.metadata.isFeatureSplit) return SplitGroupKind.FEATURE
    val suffix = when {
        name.startsWith("config.") -> name.removePrefix("config.")
        ".config." in name -> name.substringAfterLast(".config.")
        else -> name
    }
    return when {
        suffix in abiNames -> SplitGroupKind.ABI
        suffix in densityNames || numericDensity.matches(suffix) -> SplitGroupKind.DENSITY
        languageName.matches(suffix) -> SplitGroupKind.LANGUAGE
        entry.nativeAbis.size == 1 -> SplitGroupKind.ABI
        else -> SplitGroupKind.OTHER
    }
}

internal fun canInstallSelection(ui: UiState): Boolean =
    ui.phase == OperationPhase.READY && ui.prepared != null && ui.selectedPaths.isNotEmpty() &&
        !ui.savingSelection && ui.selectionSaveError == null &&
        !ui.selectionValidationPending && ui.selectionError == null

internal fun copyProgressFraction(copiedBytes: Long, sourceBytes: Long?): Float? =
    sourceBytes?.takeIf { it > 0 }?.let { (copiedBytes.coerceAtLeast(0).toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
