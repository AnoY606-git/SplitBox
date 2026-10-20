// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

// Это бюджет индекса ZIP, а не число устанавливаемых APK. Ресурсов одного APK бывает больше 65535.
internal const val MAX_ZIP_ENTRIES = 100_000
internal const val MAX_PACKAGE_APKS = 4096
private const val MAX_CENTRAL_DIRECTORY_BYTES = 32L * 1024 * 1024
private const val UINT32_MAX = 0xffffffffL

/** Ограничиваем память ZipFile до его открытия, проверяя классический ZIP и single-disk ZIP64.
 * PKWARE APPNOTE 4.3.12–16, 4.5.3: размеры/смещения ZIP64 unsigned, порядок extra-полей условный.
 * Каталог читается по одной записи; размер payload не определяет расход памяти проверки. */
internal fun openCheckedZip(file: File, isCancelled: () -> Boolean = { false }): ZipFile {
    RandomAccessFile(file, "r").use { input ->
        checkCancelled(isCancelled)
        val directory = readDirectory(input)
        checkDirectoryEntries(input, directory, isCancelled)
    }
    checkCancelled(isCancelled)
    return ZipFile(file)
}

private data class ZipDirectory(val entries: Long, val size: Long, val offset: Long, val end: Long)
private data class ZipEntryLocation(val size: Long, val compressedSize: Long, val offset: Long, val disk: Long)
private fun malformed(reason: String): Nothing = throw PreparationException(message("zip_structure_corrupted_zip_1", "Повреждённый ZIP: %1\$s", nestedMessage(reason)))
private fun ByteBuffer.u16(at: Int) = getShort(at).toInt() and 0xffff
private fun ByteBuffer.u32(at: Int) = getInt(at).toLong() and UINT32_MAX
private fun ByteBuffer.u64(at: Int): Long = getLong(at).also {
    if (it < 0) malformed(message("zip_structure_zip64_contains_a_number_outside_the_supported", "ZIP64 содержит число вне поддерживаемого диапазона"))
}
private fun RandomAccessFile.readRecord(size: Int): ByteBuffer =
    ByteBuffer.wrap(ByteArray(size).also { readFully(it) }).order(ByteOrder.LITTLE_ENDIAN)

private fun readDirectory(input: RandomAccessFile): ZipDirectory {
    val length = input.length()
    if (length < 22) malformed(message("zip_structure_central_directory_is_missing", "отсутствует центральный каталог"))
    val tailSize = minOf(length, 65557L).toInt()
    input.seek(length - tailSize)
    val tail = input.readRecord(tailSize)
    val end = (tailSize - 22 downTo 0).firstOrNull {
        tail.getInt(it) == 0x06054b50 && it + 22 + tail.u16(it + 20) == tailSize
    } ?: malformed(message("zip_structure_eocd_is_missing", "отсутствует EOCD"))
    val endOffset = length - tailSize + end
    if (tail.u16(end + 4) != 0 || tail.u16(end + 6) != 0)
        malformed(message("zip_structure_multi_volume_zip_is_not_supported", "многотомный ZIP не поддерживается"))
    val diskEntries = tail.u16(end + 8).toLong()
    val entries = tail.u16(end + 10).toLong()
    val size = tail.u32(end + 12)
    val offset = tail.u32(end + 16)
    val needsZip64 = diskEntries == 65535L || entries == 65535L || size == UINT32_MAX || offset == UINT32_MAX
    val locator = if (endOffset >= 20) {
        input.seek(endOffset - 20)
        input.readRecord(20).takeIf { it.getInt(0) == 0x07064b50 }
    } else null
    val directory = if (locator != null) {
        if (locator.u32(4) != 0L || locator.u32(16) != 1L) malformed(message("zip_structure_multi_volume_zip64_is_not_supported", "многотомный ZIP64 не поддерживается"))
        val zip64Offset = locator.u64(8)
        val locatorOffset = endOffset - 20
        if (zip64Offset > locatorOffset - 56) malformed(message("zip_structure_invalid_zip64_eocd_offset", "неверное смещение ZIP64 EOCD"))
        input.seek(zip64Offset)
        val record = input.readRecord(56)
        if (record.getInt(0) != 0x06064b50) malformed(message("zip_structure_zip64_eocd_is_missing", "отсутствует ZIP64 EOCD"))
        val recordSize = record.u64(4)
        // Вычитание после проверки границ не допускает overflow при сложении смещения и длины.
        if (recordSize < 44 || recordSize > MAX_CENTRAL_DIRECTORY_BYTES ||
            recordSize != locatorOffset - zip64Offset - 12) malformed(message("zip_structure_invalid_zip64_eocd_size", "неверный размер ZIP64 EOCD"))
        if (record.u32(16) != 0L || record.u32(20) != 0L || record.u64(24) != record.u64(32))
            malformed(message("zip_structure_multi_volume_zip64_or_mismatched_entry_counts", "многотомный ZIP64 или разные счётчики записей"))
        val result = ZipDirectory(record.u64(32), record.u64(40), record.u64(48), zip64Offset)
        if (diskEntries != 65535L && diskEntries != result.entries ||
            entries != 65535L && entries != result.entries ||
            size != UINT32_MAX && size != result.size || offset != UINT32_MAX && offset != result.offset)
            malformed(message("zip_structure_eocd_and_zip64_eocd_contradict_each_other", "EOCD и ZIP64 EOCD противоречат друг другу"))
        result
    } else {
        if (needsZip64) malformed(message("zip_structure_required_zip64_locator_is_missing", "отсутствует обязательный ZIP64 locator"))
        if (diskEntries != entries) malformed(message("zip_structure_multi_volume_zip_is_not_supported", "многотомный ZIP не поддерживается"))
        ZipDirectory(entries, size, offset, endOffset)
    }
    if (directory.entries > MAX_ZIP_ENTRIES)
        throw PreparationException(message("safe_archive_extractor_zip_contains_more_than_1_entries", "ZIP содержит больше %1\$s записей", MAX_ZIP_ENTRIES))
    if (directory.size > MAX_CENTRAL_DIRECTORY_BYTES || directory.offset > directory.end ||
        directory.size != directory.end - directory.offset || directory.entries > directory.size / 46)
        malformed(message("zip_structure_invalid_or_oversized_zip_central_directory", "некорректный или слишком большой центральный каталог ZIP"))
    return directory
}

private fun checkDirectoryEntries(input: RandomAccessFile, directory: ZipDirectory, isCancelled: () -> Boolean) {
    var position = directory.offset
    var count = 0L
    while (position < directory.end) {
        checkCancelled(isCancelled)
        if (count >= directory.entries || directory.end - position < 46)
            malformed(message("zip_structure_entry_count_or_central_directory_size_does", "число записей или размер центрального каталога не совпадает с EOCD"))
        input.seek(position)
        val header = input.readRecord(46)
        if (header.getInt(0) != 0x02014b50) malformed(message("zip_structure_invalid_central_directory_entry_signature", "неверная сигнатура записи центрального каталога"))
        val flags = header.u16(8)
        val method = header.u16(10)
        if (flags and 0x2041 != 0 || method != ZipEntry.STORED && method != ZipEntry.DEFLATED)
            malformed(message("zip_structure_unsupported_compression_or_encrypted_zip", "неподдерживаемое сжатие или зашифрованный ZIP"))
        val nameSize = header.u16(28)
        val extraSize = header.u16(30)
        val variableSize = nameSize + extraSize + header.u16(32)
        if (nameSize == 0 || nameSize > 16384 || variableSize.toLong() > directory.end - position - 46)
            malformed(message("zip_structure_invalid_central_directory_entry_length", "неверная длина записи центрального каталога"))
        val name = ByteArray(nameSize).also { input.readFully(it) }
        val extra = input.readRecord(extraSize)
        val location = readZip64Extra(extra, ZipEntryLocation(header.u32(24), header.u32(20), header.u32(42), header.u16(34).toLong()))
        if (location.disk != 0L) malformed(message("zip_structure_entry_starts_on_a_different_zip_disk", "запись начинается на другом диске ZIP"))
        if (method == ZipEntry.STORED && location.size != location.compressedSize)
            malformed(message("zip_structure_uncompressed_zip_entry_sizes_do_not_match", "размеры несжатой записи ZIP не совпадают"))
        checkLocalEntry(input, directory.offset, location, name, method, flags, header.u32(16))
        position += 46 + variableSize
        count++
    }
    if (count != directory.entries) malformed(message("zip_structure_central_directory_entry_count_does_not_match", "число записей центрального каталога не совпадает с EOCD"))
}

private fun readZip64Extra(extra: ByteBuffer, original: ZipEntryLocation): ZipEntryLocation {
    var result = original
    var position = 0
    var found = false
    val needed = original.size == UINT32_MAX || original.compressedSize == UINT32_MAX ||
        original.offset == UINT32_MAX || original.disk == 65535L
    while (position < extra.limit()) {
        if (extra.limit() - position < 4) malformed(message("zip_structure_truncated_zip_extra_field_header", "оборванный заголовок extra-поля ZIP"))
        val tag = extra.u16(position)
        val size = extra.u16(position + 2)
        position += 4
        if (size > extra.limit() - position) malformed(message("zip_structure_truncated_zip_extra_field", "оборванное extra-поле ZIP"))
        if (tag == 1) {
            if (found) malformed(message("zip_structure_duplicate_zip64_extra_field", "повторяющееся extra-поле ZIP64"))
            found = true
            val end = position + size
            var cursor = position
            fun value64(old: Long): Long {
                if (old != UINT32_MAX) return old
                if (end - cursor < 8) malformed(message("zip_structure_truncated_zip64_extra_field", "оборванное extra-поле ZIP64"))
                return extra.u64(cursor).also { cursor += 8 }
            }
            val expanded = value64(original.size)
            val compressed = value64(original.compressedSize)
            val offset = value64(original.offset)
            val disk = if (original.disk == 65535L) {
                if (end - cursor < 4) malformed(message("zip_structure_truncated_zip64_extra_field", "оборванное extra-поле ZIP64"))
                extra.u32(cursor).also { cursor += 4 }
            } else original.disk
            if (needed && cursor != end) malformed(message("zip_structure_invalid_zip64_extra_field_size", "неверный размер extra-поля ZIP64"))
            result = ZipEntryLocation(expanded, compressed, offset, disk)
        }
        position += size
    }
    if (needed && !found) malformed(message("zip_structure_required_zip64_extra_field_is_missing", "отсутствует обязательное extra-поле ZIP64"))
    return result
}

private fun checkLocalEntry(input: RandomAccessFile, centralOffset: Long, location: ZipEntryLocation,
    name: ByteArray, method: Int, flags: Int, crc: Long) {
    if (location.offset > centralOffset - 30) malformed(message("zip_structure_local_zip_entry_is_outside_the_data", "локальная запись ZIP выходит за границы данных"))
    input.seek(location.offset)
    val local = input.readRecord(30)
    if (local.getInt(0) != 0x04034b50 || local.u16(6) != flags || local.u16(8) != method || local.u16(26) != name.size)
        malformed(message("zip_structure_local_zip_entry_does_not_match_the", "локальная запись ZIP не совпадает с центральным каталогом"))
    val headerSize = 30L + local.u16(26) + local.u16(28)
    if (headerSize > centralOffset - location.offset || location.compressedSize > centralOffset - location.offset - headerSize)
        malformed(message("zip_structure_zip_entry_data_is_outside_the_archive", "данные записи ZIP выходят за границы архива"))
    val localName = ByteArray(name.size).also { input.readFully(it) }
    if (!localName.contentEquals(name)) malformed(message("zip_structure_local_zip_entry_and_directory_names_differ", "имена локальной записи ZIP и каталога различаются"))
    // При data descriptor размеры/CRC локального заголовка ещё не известны; истина — CEN.
    if (flags and 8 == 0) {
        val original = ZipEntryLocation(local.u32(22), local.u32(18), 0, 0)
        // В обычных APK local extra также содержит padding zipalign, не обязательно TLV.
        val localLocation = if (original.size == UINT32_MAX || original.compressedSize == UINT32_MAX)
            readZip64Extra(input.readRecord(local.u16(28)), original) else original
        if (localLocation.size != location.size || localLocation.compressedSize != location.compressedSize || local.u32(14) != crc)
            malformed(message("zip_structure_local_zip_entry_size_or_crc_does", "размеры или CRC локальной записи ZIP не совпадают с каталогом"))
    }
}
