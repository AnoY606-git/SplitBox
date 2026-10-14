// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.inspector

import dev.splitbox.core.PermissionDeclaration
import dev.splitbox.core.PreparedPackage

data class PackageVersion(val name: String, val code: Long)
sealed interface InstalledPackageResult {
    data class Found(val version: PackageVersion) : InstalledPackageResult
    data object Unavailable : InstalledPackageResult
    data object Failure : InstalledPackageResult
}
fun interface InstalledPackageReader {
    fun read(packageName: String): InstalledPackageResult
}
enum class VersionRelation { UPDATE, SAME, DOWNGRADE, UNAVAILABLE, FAILURE }
data class VersionComparison(val incoming: PackageVersion, val installed: PackageVersion?, val relation: VersionRelation)
data class PermissionGroup(val name: String, val declarations: List<PermissionDeclaration>)
data class PermissionSummary(val permissions: List<PermissionGroup>, val complete: Boolean) {
    fun search(query: String): List<PermissionGroup> = permissions.filter { it.name.contains(query.trim(), ignoreCase = true) }
}
data class PackageInspection(val comparison: VersionComparison, val permissions: PermissionSummary)
fun compareVersions(incoming: PackageVersion, installed: InstalledPackageResult): VersionComparison {
    val version = (installed as? InstalledPackageResult.Found)?.version
    val relation = when (installed) {
        InstalledPackageResult.Unavailable -> VersionRelation.UNAVAILABLE
        InstalledPackageResult.Failure -> VersionRelation.FAILURE
        is InstalledPackageResult.Found -> when {
            incoming.code > installed.version.code -> VersionRelation.UPDATE
            incoming.code < installed.version.code -> VersionRelation.DOWNGRADE
            else -> VersionRelation.SAME
        }
    }
    return VersionComparison(incoming, version, relation)
}

fun summarizePermissions(prepared: PreparedPackage, selected: Set<String>): PermissionSummary {
    val entries = prepared.apks.filter { it.archivePath in selected }
    val complete = selected.isNotEmpty() && entries.size == selected.size && entries.all { it.metadata.requestedPermissions != null }
    val permissions = entries.flatMap { it.metadata.requestedPermissions.orEmpty() }.distinct()
        .groupBy { it.name }.toSortedMap().map { (name, declarations) ->
            PermissionGroup(name, declarations.sortedWith(compareBy({ it.minSdk }, { it.maxSdk ?: Int.MAX_VALUE })))
        }
    return PermissionSummary(permissions, complete)
}
