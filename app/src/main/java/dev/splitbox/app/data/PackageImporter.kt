// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import android.net.Uri
import dev.splitbox.core.*
import java.io.File

internal data class PreparedImport(val prepared: PreparedPackage, val source: File?, val sha256: String?)

/** Вызывается на IO: метаданные провайдера, потоки и инспекция принадлежат одному импорту. */
internal class PackageImporter(private val stager: PackageStager) {
    fun prepare(uris: List<Uri>, directory: File, device: DeviceProfile, options: PrepareOptions,
        computeSha256: Boolean, cancelled: () -> Boolean, onCopy: (Long?, Long) -> Unit,
        onAnalysis: (String, Float?) -> Unit): PreparedImport {
        val total = ImportSelection.knownTotal(uris.map(stager::sourceSize))
        onCopy(total, 0)
        val progress: (Long) -> Unit = { onCopy(total, it) }
        if (uris.size == 1) {
            val uri = uris.single()
            val name = stager.name(uri)
            val source = File(directory, "source.bin")
            val copied = stager.copy(uri, source, options.maxExpandedBytes, cancelled, progress, computeSha256)
            onAnalysis(name, null)
            val prepared = ArchivePreparer().prepare(source, name, directory, device, options, onAnalysis, cancelled)
            return PreparedImport(prepared, source, copied.sha256)
        }
        val staged = MultiApkStager.stage(uris, directory, options.maxExpandedBytes, stager::name,
            stager::openSource, cancelled, progress)
        onAnalysis("Набор из ${uris.size} APK", null)
        val prepared = StagedApkPreparer().prepare(staged, directory, device, options, onAnalysis, cancelled)
        // У набора нет единственного исходного файла и единственного SHA-256.
        return PreparedImport(prepared, null, null)
    }
}
