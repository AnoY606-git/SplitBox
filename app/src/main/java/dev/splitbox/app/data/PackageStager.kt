// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import dev.splitbox.core.message
import dev.splitbox.core.nestedMessage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.splitbox.core.ApkEntry
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import com.android.apksig.ApkVerifier

class PackageStager(private val context: Context) {
    val root = File(context.cacheDir, "packages").apply { mkdirs() }
    private val cacheSize = CacheSizeIndex(root)
    fun newDirectory() = File(root, UUID.randomUUID().toString()).apply { check(mkdirs()) { message("package_stager_could_not_create_the_working_directory", "Не удалось создать рабочий каталог") } }
    fun name(uri: Uri): String {
        if (uri.scheme == "content") runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0)?.take(240)?.replace('\u001e', '\uFFFD') ?: message("install_journal_archive", "Архив")
            }
        }
        return uri.lastPathSegment?.take(240)?.replace('\u001e', '\uFFFD') ?: message("install_journal_archive", "Архив")
    }
    /** Размер провайдера — только подсказка для прогресса; лимит проверяется по прочитанным байтам. */
    fun sourceSize(uri: Uri): Long? {
        if (uri.scheme == "file") return runCatching {
            File(requireNotNull(uri.path) { message("package_stager_the_file_uri_has_no_path", "В файловом URI отсутствует путь") }).takeIf { it.isFile }?.length()
        }.getOrNull()
        if (uri.scheme != "content") return null
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it >= 0 } else null
            }
        }.getOrNull()
    }
    fun openSource(uri: Uri): InputStream {
        require(uri.scheme == "content" || uri.scheme == "file") { message("split_box_view_model_only_content_file_uris_are_supported", "Поддерживаются только content/file URI") }
        return try {
            if (uri.scheme == "file") FileInputStream(File(requireNotNull(uri.path) { message("package_stager_the_file_uri_has_no_path", "В файловом URI отсутствует путь") }))
            else context.contentResolver.openInputStream(uri) ?: throw IOException(message("package_stager_the_source_did_not_open_the_file", "Источник не открыл файл"))
        } catch (e: SecurityException) {
            throw IOException(message("package_stager_uri_access_has_expired_or_was_not", "Доступ к URI истёк или не разрешён. Выберите файл заново через системный диалог."), e)
        }
    }
    // Сохраняет исходный вызов с progress в trailing lambda.
    fun copy(uri: Uri, destination: File, maxBytes: Long, cancelled: () -> Boolean,
        progress: (Long) -> Unit): SourceCopyResult = copy(uri, destination, maxBytes, cancelled, progress, false)
    fun copy(uri: Uri, destination: File, maxBytes: Long, cancelled: () -> Boolean, progress: (Long) -> Unit,
        computeSha256: Boolean = false): SourceCopyResult {
        require(uri.scheme == "content" || uri.scheme == "file") { message("split_box_view_model_only_content_file_uris_are_supported", "Поддерживаются только content/file URI") }
        try {
            val storage = AvailableStorage(destination.parentFile!!)
            val source = openSource(uri)
            return source.use { input -> destination.outputStream().use { output ->
                val result = SourceCopier.copy(input, output, maxBytes, computeSha256, cancelled, progress) { required ->
                    PackageSizePolicy.requireSpace(required, storage.bytes())
                }
                output.fd.sync()
                result
            } }
        } catch (e: SecurityException) {
            destination.delete()
            throw IOException(message("package_stager_uri_access_has_expired_or_was_not", "Доступ к URI истёк или не разрешён. Выберите файл заново через системный диалог."), e)
        } catch (e: Exception) {
            destination.delete()
            throw e
        }
    }
    fun sha256(file: File, cancelled: () -> Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                if (cancelled()) throw java.util.concurrent.CancellationException(message("package_stager_hash_calculation_canceled", "Вычисление хеша отменено"))
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun verifySignatures(apks: List<ApkEntry>, sdk: Int, cancelled: () -> Boolean) {
        require(apks.isNotEmpty() && apks.size <= 4096) { message("package_stager_invalid_number_of_apks", "Некорректное количество APK") }
        var expected: Set<String>? = null
        apks.forEach { entry ->
            if (cancelled()) throw java.util.concurrent.CancellationException(message("package_stager_verification_canceled", "Проверка отменена"))
            val result = ApkVerifier.Builder(entry.file).setMinCheckedPlatformVersion(sdk)
                .setMaxCheckedPlatformVersion(sdk).build().verify()
            check(result.isVerified) {
                message("package_stager_invalid_signature_for", "Подпись %1\$s недействительна: %2\$s", entry.archivePath, result.errors.joinToString().take(1000))
            }
            val certificates = result.signerCertificates.map { cert ->
                MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
            }.toSet()
            check(certificates.isNotEmpty()) { message("package_stager_the_apk_contains_no_verified_certificate", "APK не содержит проверенного сертификата") }
            if (expected == null) expected = certificates
            else check(expected == certificates) { message("package_stager_the_apks_are_signed_with_different_certificates", "APK подписаны разными сертификатами: %1\$s", entry.archivePath) }
        }
    }
    fun bytes(): Long = cacheSize.bytes()
    fun rescan(): Long = cacheSize.rescan()
    fun refreshDirectory(directory: File): Long = cacheSize.refreshDirectory(directory)
    fun delete(directory: File) { cacheSize.delete(directory) }
    fun clearExcept(protected: Set<String>) {
        root.listFiles()?.filterNot { it.name in protected }?.forEach(::delete)
    }
}
