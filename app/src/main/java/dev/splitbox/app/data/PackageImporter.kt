// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import dev.splitbox.core.message
import dev.splitbox.core.nestedMessage

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
        val reporting = dev.splitbox.app.installer.TransferProgress(total) { bytes, _ -> onCopy(total, bytes) }
        val progress: (Long) -> Unit = reporting::report
        val storage = AvailableStorage(directory)
        val sourceBudget = PackageSizePolicy.copyBudget(options.maxExpandedBytes, storage.bytes())
        if (uris.size == 1) {
            val uri = uris.single()
            val name = stager.name(uri)
            val source = File(directory, "source.bin")
            val copied = stager.copy(uri, source, sourceBudget, cancelled, progress, computeSha256)
            onCopy(total, copied.bytes)
            onAnalysis(name, null)
            val prepared = ArchivePreparer().prepare(source, name, directory, device, options, onAnalysis, cancelled)
            return PreparedImport(prepared, source, copied.sha256)
        }
        val staged = MultiApkStager.stage(uris, directory, sourceBudget, stager::name,
            stager::openSource, cancelled, progress) { required ->
            PackageSizePolicy.requireSpace(required, storage.bytes())
        }
        onCopy(total, staged.fold(0L) { sum, entry -> Math.addExact(sum, entry.file.length()) })
        onAnalysis(message("package_importer_set_of_apks", "Набор из %1\$s APK", uris.size), null)
        val prepared = StagedApkPreparer().prepare(staged, directory, device, options, onAnalysis, cancelled)
        // У набора нет единственного исходного файла и единственного SHA-256.
        return PreparedImport(prepared, null, null)
    }
}
