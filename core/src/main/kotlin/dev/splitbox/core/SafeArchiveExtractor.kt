// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.core

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal const val MAX_METADATA_BYTES = 4 * 1024 * 1024
internal const val MAX_ZIP_ENTRIES = 4096
internal fun checkCancelled(isCancelled: () -> Boolean) {
    if (isCancelled() || Thread.currentThread().isInterrupted) throw PreparationException("Операция отменена")
}

/** Проверяем имена всех записей ZIP, включая не-APK. Имена файлов не передаются в оболочку. */
internal fun safeArchivePath(name: String, directory: Boolean = false): String {
    if (name.isEmpty() || name.length > 4096 || name.startsWith('/') || name.contains('\\') ||
        name.contains('\u0000') || Regex("^[A-Za-z]:").containsMatchIn(name)) {
        throw PreparationException("Небезопасный путь ZIP: ${name.take(120)}")
    }
    val clean = if (directory) name.removeSuffix("/") else name
    if (clean.isEmpty() || clean.split('/').any { it.isEmpty() || it == "." || it == ".." })
        throw PreparationException("Небезопасный путь ZIP: ${name.take(120)}")
    return clean
}

/** Проверяем ограничения EOCD до выделения памяти под индекс центрального каталога ZipFile. */
internal fun openCheckedZip(file: File): ZipFile {
    RandomAccessFile(file, "r").use { input ->
        val length = input.length()
        if (length < 22) throw PreparationException("Повреждённый ZIP: отсутствует центральный каталог")
        val tailSize = minOf(length, 65557L).toInt()
        val tail = ByteArray(tailSize)
        input.seek(length - tailSize); input.readFully(tail)
        val bytes = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        fun ushort(at: Int) = bytes.getShort(at).toInt() and 65535
        var end = -1
        for (index in tailSize - 22 downTo 0) {
            if (bytes.getInt(index) == 0x06054b50 && index + 22 + ushort(index + 20) == tailSize) { end = index; break }
        }
        if (end < 0) throw PreparationException("Повреждённый ZIP: отсутствует EOCD")
        val entries = ushort(end + 10)
        val size = bytes.getInt(end + 12).toLong() and 0xffffffffL
        val offset = bytes.getInt(end + 16).toLong() and 0xffffffffL
        if (entries == 65535 || ushort(end + 8) == 65535 || size == 0xffffffffL || offset == 0xffffffffL)
            throw PreparationException("Центральный каталог ZIP64 не поддерживается")
        if (ushort(end + 4) != 0 || ushort(end + 6) != 0 || ushort(end + 8) != entries)
            throw PreparationException("Многотомный ZIP не поддерживается")
        if (entries > MAX_ZIP_ENTRIES) throw PreparationException("ZIP содержит больше $MAX_ZIP_ENTRIES записей")
        if (size > 32L * 1024 * 1024 || offset + size != length - tailSize + end)
            throw PreparationException("Некорректный или слишком большой центральный каталог ZIP")
    }
    return ZipFile(file)
}

internal fun checkedZipEntries(zip: ZipFile): List<ZipEntry> {
    val entries = mutableListOf<ZipEntry>()
    val seen = mutableMapOf<String, Boolean>()
    val enumeration = zip.entries()
    while (enumeration.hasMoreElements()) {
        if (entries.size >= MAX_ZIP_ENTRIES) throw PreparationException("ZIP содержит больше $MAX_ZIP_ENTRIES записей")
        val entry = enumeration.nextElement()
        val path = safeArchivePath(entry.name, entry.isDirectory)
        if (seen.put(path, entry.isDirectory) != null) throw PreparationException("Дубликат пути ZIP: $path")
        if (entry.method != ZipEntry.DEFLATED && entry.method != ZipEntry.STORED)
            throw PreparationException("Неподдерживаемое сжатие или зашифрованный ZIP")
        entries += entry
    }
    seen.forEach { (path, _) ->
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotEmpty()) {
            if (seen[parent] == false) throw PreparationException("Конфликт файла и каталога ZIP: $parent")
            parent = parent.substringBeforeLast('/', "")
        }
    }
    return entries
}

internal class ByteBudget(private val maximum: Long) {
    var consumed: Long = 0; private set
    init { if (maximum <= 0) throw PreparationException("Лимит распаковки должен быть положительным") }
    fun consume(count: Int) {
        if (count.toLong() > maximum - consumed) throw PreparationException("Превышен лимит распаковки ($maximum байт)")
        consumed += count
    }
}

internal fun copyBounded(input: InputStream, output: OutputStream, budget: ByteBudget,
    isCancelled: () -> Boolean, checkSpace: File? = null, crc: CRC32? = null,
    onBytes: (Long) -> Unit = {}): Long {
    val buffer = ByteArray(64 * 1024)
    var bytes = 0L
    while (true) {
        checkCancelled(isCancelled)
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        budget.consume(count)
        if (checkSpace != null && checkSpace.usableSpace < count.toLong()) throw PreparationException("Недостаточно свободного места")
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
        openCheckedZip(source).use { zip ->
            val entries = checkedZipEntries(zip)
            if (entries.isEmpty()) throw PreparationException("Архив пуст")
            entries.forEachIndexed { index, entry ->
                checkCancelled(isCancelled)
                val path = safeArchivePath(entry.name, entry.isDirectory)
                val destination = File(workDir, path)
                if (!destination.canonicalPath.startsWith(workDir.canonicalPath + File.separator))
                    throw PreparationException("Путь ZIP выходит за рабочий каталог")
                if (entry.isDirectory) {
                    if (!destination.mkdirs() && !destination.isDirectory) throw PreparationException("Не удалось создать рабочий каталог")
                } else {
                    if (!destination.parentFile.mkdirs() && !destination.parentFile.isDirectory)
                        throw PreparationException("Не удалось создать рабочий каталог")
                    if (!destination.createNewFile()) throw PreparationException("Рабочий файл уже существует: $path")
                    val crc = CRC32()
                    val actual = zip.getInputStream(entry).use { input -> destination.outputStream().use { out ->
                        copyBounded(input, out, budget, isCancelled, workDir, crc)
                    } }
                    if (entry.size >= 0 && entry.size != actual || entry.crc >= 0 && entry.crc != crc.value)
                        throw PreparationException("Повреждённая ZIP запись: $path")
                    result[path] = destination
                }
                onProgress("Распаковка: ${index + 1}/${entries.size}", (index + 1).toFloat() / entries.size)
            }
        }
        return result
    }
}
