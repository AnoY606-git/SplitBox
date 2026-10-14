// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

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
    fun newDirectory() = File(root, UUID.randomUUID().toString()).apply { check(mkdirs()) { "Не удалось создать рабочий каталог" } }
    fun name(uri: Uri): String {
        if (uri.scheme == "content") runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0)?.take(240) ?: "Архив"
            }
        }
        return uri.lastPathSegment?.take(240) ?: "Архив"
    }
    /** Размер провайдера — только подсказка для прогресса; лимит проверяется по прочитанным байтам. */
    fun sourceSize(uri: Uri): Long? {
        if (uri.scheme == "file") return runCatching {
            File(requireNotNull(uri.path) { "В файловом URI отсутствует путь" }).takeIf { it.isFile }?.length()
        }.getOrNull()
        if (uri.scheme != "content") return null
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it >= 0 } else null
            }
        }.getOrNull()
    }
    fun openSource(uri: Uri): InputStream {
        require(uri.scheme == "content" || uri.scheme == "file") { "Поддерживаются только content/file URI" }
        return try {
            if (uri.scheme == "file") FileInputStream(File(requireNotNull(uri.path) { "В файловом URI отсутствует путь" }))
            else context.contentResolver.openInputStream(uri) ?: throw IOException("Источник не открыл файл")
        } catch (e: SecurityException) {
            throw IOException("Доступ к URI истёк или не разрешён. Выберите файл заново через системный диалог.", e)
        }
    }
    // Сохраняет исходный вызов с progress в trailing lambda.
    fun copy(uri: Uri, destination: File, maxBytes: Long, cancelled: () -> Boolean,
        progress: (Long) -> Unit): SourceCopyResult = copy(uri, destination, maxBytes, cancelled, progress, false)
    fun copy(uri: Uri, destination: File, maxBytes: Long, cancelled: () -> Boolean, progress: (Long) -> Unit,
        computeSha256: Boolean = false): SourceCopyResult {
        require(uri.scheme == "content" || uri.scheme == "file") { "Поддерживаются только content/file URI" }
        try {
            val source = openSource(uri)
            return source.use { input -> destination.outputStream().use { output ->
                val result = SourceCopier.copy(input, output, maxBytes, computeSha256, cancelled, progress)
                output.fd.sync()
                result
            } }
        } catch (e: SecurityException) {
            destination.delete()
            throw IOException("Доступ к URI истёк или не разрешён. Выберите файл заново через системный диалог.", e)
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
                if (cancelled()) throw java.util.concurrent.CancellationException("Вычисление хеша отменено")
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun verifySignatures(apks: List<ApkEntry>, sdk: Int, cancelled: () -> Boolean) {
        require(apks.isNotEmpty() && apks.size <= 4096) { "Некорректное количество APK" }
        var expected: Set<String>? = null
        apks.forEach { entry ->
            if (cancelled()) throw java.util.concurrent.CancellationException("Проверка отменена")
            val result = ApkVerifier.Builder(entry.file).setMinCheckedPlatformVersion(sdk)
                .setMaxCheckedPlatformVersion(sdk).build().verify()
            check(result.isVerified) {
                "Подпись ${entry.archivePath} недействительна: ${result.errors.joinToString().take(1000)}"
            }
            val certificates = result.signerCertificates.map { cert ->
                MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
            }.toSet()
            check(certificates.isNotEmpty()) { "APK не содержит проверенного сертификата" }
            if (expected == null) expected = certificates
            else check(expected == certificates) { "APK подписаны разными сертификатами: ${entry.archivePath}" }
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
