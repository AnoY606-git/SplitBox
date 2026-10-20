// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.core

import java.io.File
import java.util.zip.CRC32

/** Общая безопасная инспекция APK из архива и прямого набора. */
internal class ApkInspector {
    fun inspect(file: File, archivePath: String, isCancelled: () -> Boolean): ApkEntry {
        openCheckedZip(file, isCancelled).use { zip ->
            val entries = checkedZipEntries(zip, isCancelled)
            if (entries.any { it.name == "apex_manifest.pb" || it.name == "apex_payload.img" })
                throw PreparationException(message("apk_inspector_apex_is_not_supported_by_the_apk", "APEX не поддерживается установщиком APK"))
            val manifest = entries.singleOrNull { it.name == "AndroidManifest.xml" && !it.isDirectory }
                ?: throw PreparationException(message("apk_inspector_apk_has_no_androidmanifest_xml_1", "APK без AndroidManifest.xml: %1\$s", archivePath))
            if (manifest.size > MAX_METADATA_BYTES) throw PreparationException(message("apk_inspector_manifest_exceeds_4_mib", "Манифест превышает 4 МиБ"))
            val bytes = zip.getInputStream(manifest).use { readMetadata(it, isCancelled) }
            val crc = CRC32().apply { update(bytes) }
            if (manifest.crc >= 0 && crc.value != manifest.crc) throw PreparationException(message("apk_inspector_corrupted_androidmanifest_xml", "Повреждённый AndroidManifest.xml"))
            val metadata = BinaryManifestReader().read(bytes)
            val abis = entries.mapNotNull { entry ->
                if (!entry.isDirectory && entry.name.startsWith("lib/") && entry.name.endsWith(".so")) {
                    val parts = entry.name.split('/')
                    if (parts.size != 3 || parts[1] !in knownAbis) throw PreparationException(message("apk_inspector_unknown_native_abi_in_apk", "Неизвестная нативная ABI в APK"))
                    parts[1]
                } else null
            }.toSet()
            return ApkEntry(file, archivePath, metadata, file.length(), abis)
        }
    }
}
