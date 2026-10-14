// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.installer

import dev.splitbox.app.model.*
import dev.splitbox.core.ApkEntry
import dev.splitbox.core.PreparedPackage

data class InstallRequest(val prepared: PreparedPackage, val apks: List<ApkEntry>,
    val settings: AppSettings, val appLabel: String, val userId: Int, val onCommitted: () -> Unit = {}, val onCommitStarted: () -> Unit = {}, val onSessionCreated: (Int) -> Unit = {})
sealed interface BackendOutcome {
    data class Finished(val result: OperationResult) : BackendOutcome
    data class SystemPending(val sessionId: Int) : BackendOutcome
}
interface InstallBackend {
    suspend fun install(request: InstallRequest, progress: (String, Float?) -> Unit): BackendOutcome
}
class BackendRegistry(private val system: SystemInstallBackend, private val root: RootInstallBackend,
    private val shizuku: ShizukuInstallBackend) {
    fun select(mode: BackendMode): InstallBackend = when (mode) {
        BackendMode.SYSTEM -> system
        BackendMode.ROOT -> root
        BackendMode.SHIZUKU -> shizuku
    }
}
