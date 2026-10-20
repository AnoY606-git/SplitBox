// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.core

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal const val MAX_METADATA_BYTES = 4 * 1024 * 1024
internal fun checkCancelled(isCancelled: () -> Boolean) {
    if (isCancelled() || Thread.currentThread().isInterrupted) throw CancellationException(message("split_box_view_model_operation_canceled", "Операция отменена"))
}

/** Проверяем имена всех записей ZIP, включая не-APK. Имена файлов не передаются в оболочку. */
internal fun safeArchivePath(name: String, directory: Boolean = false): String {
    if (name.isEmpty() || name.length > 4096 || name.startsWith('/') || name.contains('\\') ||
        name.contains('\u0000') || Regex("^[A-Za-z]:").containsMatchIn(name)) {
        throw PreparationException(message("safe_archive_extractor_unsafe_zip_path_1", "Небезопасный путь ZIP: %1\$s", name.take(120)))
    }
    val clean = if (directory) name.removeSuffix("/") else name
    if (clean.isEmpty() || clean.split('/').any { it.isEmpty() || it == "." || it == ".." })
        throw PreparationException(message("safe_archive_extractor_unsafe_zip_path_1", "Небезопасный путь ZIP: %1\$s", name.take(120)))
    return clean
}

internal fun checkedZipEntries(zip: ZipFile, isCancelled: () -> Boolean = { false }): List<ZipEntry> {
    val entries = mutableListOf<ZipEntry>()
    val seen = java.util.TreeMap<String, Boolean>()
    val enumeration = zip.entries()
    while (enumeration.hasMoreElements()) {
        checkCancelled(isCancelled)
        if (entries.size >= MAX_ZIP_ENTRIES) throw PreparationException(message("safe_archive_extractor_zip_contains_more_than_1_entries", "ZIP содержит больше %1\$s записей", MAX_ZIP_ENTRIES))
        val entry = enumeration.nextElement()
        val path = safeArchivePath(entry.name, entry.isDirectory)
        if (seen.put(path, entry.isDirectory) != null) throw PreparationException(message("safe_archive_extractor_duplicate_zip_path_1", "Дубликат пути ZIP: %1\$s", path))
        if (entry.method != ZipEntry.DEFLATED && entry.method != ZipEntry.STORED)
            throw PreparationException(message("safe_archive_extractor_unsupported_compression_or_encrypted_zip", "Неподдерживаемое сжатие или зашифрованный ZIP"))
        entries += entry
    }
    seen.forEach { (path, directory) ->
        checkCancelled(isCancelled)
        if (!directory) {
            // Поиск первого потомка не перебирает и не копирует все префиксы глубокого пути.
            val prefix = "$path/"
            if (seen.ceilingKey(prefix)?.startsWith(prefix) == true)
                throw PreparationException(message("safe_archive_extractor_zip_file_and_directory_conflict_1", "Конфликт файла и каталога ZIP: %1\$s", path))
        }
    }
    return entries
}

internal class ByteBudget(private val maximum: Long) {
    var consumed: Long = 0; private set
    init { if (maximum <= 0) throw PreparationException(message("safe_archive_extractor_extraction_limit_must_be_positive", "Лимит распаковки должен быть положительным")) }
    fun requireAvailable(count: Long) {
        if (count < 0 || count > maximum - consumed) throw PreparationException(message("safe_archive_extractor_extraction_limit_exceeded_1_bytes", "Превышен лимит распаковки (%1\$s байт)", maximum))
    }
    fun consume(count: Int) {
        if (count < 0 || count.toLong() > maximum - consumed) throw PreparationException(message("safe_archive_extractor_extraction_limit_exceeded_1_bytes", "Превышен лимит распаковки (%1\$s байт)", maximum))
        consumed += count
    }
}

internal fun copyBounded(input: InputStream, output: OutputStream, budget: ByteBudget,
    isCancelled: () -> Boolean, checkSpace: File? = null, crc: CRC32? = null,
    onBytes: (Long) -> Unit = {}): Long {
    val buffer = ByteArray(64 * 1024)
    var bytes = 0L
    var bytesUntilSpaceCheck = 0L
    while (true) {
        checkCancelled(isCancelled)
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        budget.consume(count)
        if (checkSpace != null) {
            // statvfs на каждом 64-КиБ блоке заметно замедляет многогигабайтную распаковку.
            if (bytesUntilSpaceCheck < count) {
                val available = checkSpace.usableSpace - PREPARATION_SPACE_RESERVE_BYTES
                if (available < count) throw PreparationException(message("storage_preflight_recovery_not_enough_free_space", "Недостаточно свободного места"))
                bytesUntilSpaceCheck = minOf(available, 4L * 1024 * 1024)
            }
            bytesUntilSpaceCheck -= count
        }
        output.write(buffer, 0, count)
        crc?.update(buffer, 0, count)
        bytes += count
        onBytes(bytes)
    }
    checkCancelled(isCancelled)
    return bytes
}

internal fun readMetadata(input: InputStream, isCancelled: () -> Boolean = { false }): ByteArray =
    java.io.ByteArrayOutputStream().use { out ->
        copyBounded(input, out, ByteBudget(MAX_METADATA_BYTES.toLong()), isCancelled)
        out.toByteArray()
    }

class SafeArchiveExtractor {
    internal fun extract(source: File, workDir: File, budget: ByteBudget,
        isCancelled: () -> Boolean, onProgress: (String, Float?) -> Unit): Map<String, File> {
        val result = linkedMapOf<String, File>()
        openCheckedZip(source, isCancelled).use { zip ->
            val entries = checkedZipEntries(zip, isCancelled)
            if (entries.isEmpty()) throw PreparationException(message("safe_archive_extractor_archive_is_empty", "Архив пуст"))
            if (entries.count { !it.isDirectory && it.name.endsWith(".apk", true) } > MAX_PACKAGE_APKS)
                throw PreparationException(message("safe_archive_extractor_archive_contains_more_than_1_apks", "Архив содержит больше %1\$s APK", MAX_PACKAGE_APKS))
            entries.forEachIndexed { index, entry ->
                checkCancelled(isCancelled)
                val path = safeArchivePath(entry.name, entry.isDirectory)
                val destination = File(workDir, path)
                if (!destination.canonicalPath.startsWith(workDir.canonicalPath + File.separator))
                    throw PreparationException(message("safe_archive_extractor_zip_path_escapes_the_working_directory", "Путь ZIP выходит за рабочий каталог"))
                if (entry.isDirectory) {
                    if (!destination.mkdirs() && !destination.isDirectory) throw PreparationException(message("package_stager_could_not_create_the_working_directory", "Не удалось создать рабочий каталог"))
                } else {
                    budget.requireAvailable(entry.size)
                    if (!destination.parentFile.mkdirs() && !destination.parentFile.isDirectory)
                        throw PreparationException(message("package_stager_could_not_create_the_working_directory", "Не удалось создать рабочий каталог"))
                    if (!destination.createNewFile()) throw PreparationException(message("safe_archive_extractor_working_file_already_exists_1", "Рабочий файл уже существует: %1\$s", path))
                    val crc = CRC32()
                    val actual = zip.getInputStream(entry).use { input -> destination.outputStream().use { out ->
                        copyBounded(input, out, budget, isCancelled, workDir, crc)
                    } }
                    if (entry.size >= 0 && entry.size != actual || entry.crc >= 0 && entry.crc != crc.value)
                        throw PreparationException(message("safe_archive_extractor_corrupted_zip_entry_1", "Повреждённая ZIP запись: %1\$s", path))
                    result[path] = destination
                }
                onProgress(message("safe_archive_extractor_extracting_1_2", "Распаковка: %1\$s/%2\$s", index + 1, entries.size), (index + 1).toFloat() / entries.size)
            }
        }
        return result
    }
}
