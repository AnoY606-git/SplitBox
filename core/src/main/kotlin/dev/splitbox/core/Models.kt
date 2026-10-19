// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.core

import java.io.File
import java.io.IOException

data class DeviceProfile(val sdk: Int, val abis: List<String>, val densityDpi: Int, val locales: List<String>)
const val PREPARATION_SPACE_RESERVE_BYTES = 64L * 1024 * 1024

data class PrepareOptions(val maxExpandedBytes: Long = Long.MAX_VALUE, val autoSelect: Boolean = true)
data class ApkMetadata(
    val packageName: String, val versionCode: Long, val versionName: String,
    val minSdk: Int, val targetSdk: Int,
    val splitName: String? = null, val configForSplit: String? = null,
    val isFeatureSplit: Boolean = false, val usesSplits: List<String> = emptyList(),
    val requiredSplitTypes: Set<String> = emptySet(), val splitTypes: Set<String> = emptySet(),
    val requestedPermissions: List<PermissionDeclaration>? = null
)
data class ApkEntry(
    val file: File, val archivePath: String, val metadata: ApkMetadata,
    val size: Long, val nativeAbis: Set<String> = emptySet(),
    val selected: Boolean = true, val locked: Boolean = false, val selectionReason: String = ""
)
data class ObbEntry(val file: File, val archivePath: String, val size: Long)
data class PreparedPackage(
    val sourceName: String, val metadata: ApkMetadata, val apks: List<ApkEntry>,
    val obbFiles: List<ObbEntry>, val warnings: List<String>, val workDir: File
)
class PreparationException(message: String) : IOException(message)

/** Внутреннее представление устаревшего флага Android isSplitRequired в общей модели. */
internal const val LEGACY_ANY_SPLIT = "__splitbox_legacy_any_split__"
