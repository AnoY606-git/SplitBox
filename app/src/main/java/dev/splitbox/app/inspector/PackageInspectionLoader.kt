// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.inspector

import dev.splitbox.core.PreparedPackage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Снимок выбранных APK и запрос PackageManager обрабатываются вне потока интерфейса. */
suspend fun loadPackageInspection(prepared: PreparedPackage, selected: Set<String>, reader: InstalledPackageReader,
    dispatcher: CoroutineDispatcher): PackageInspection = withContext(dispatcher) {
    val installed = reader.read(prepared.metadata.packageName)
    currentCoroutineContext().ensureActive()
    val permissions = summarizePermissions(prepared, selected)
    currentCoroutineContext().ensureActive()
    PackageInspection(compareVersions(PackageVersion(prepared.metadata.versionName, prepared.metadata.versionCode), installed), permissions)
}
