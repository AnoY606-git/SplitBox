// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class LargeArchiveTest {
    private val device = DeviceProfile(36, listOf("x86_64"), 420, listOf("ru-RU"))

    // Sparse-файлы находятся на диске проекта, а не в /tmp, который может быть tmpfs.
    private fun workspace(block: (File) -> Unit) {
        val parent = File("build/large-archive-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "case-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    private fun reject(block: () -> Unit) {
        try { block(); fail("Ожидался отказ проверки ZIP") } catch (_: PreparationException) { }
    }

    @Test fun preparesRealAndroidManifestApkWithSixThousandResourceFiles() = workspace { root ->
        val source = File(root, "source.bin")
        val original = File(root, "original.apk")
        javaClass.getResourceAsStream("/fixtures/application.apk")!!.use { input ->
            original.outputStream().use { input.copyTo(it) }
        }
        ZipFile(original).use { zip -> ZipOutputStream(source.outputStream()).use { out ->
            zip.entries().asSequence().forEach { entry ->
                out.putNextEntry(ZipEntry(entry.name))
                zip.getInputStream(entry).use { it.copyTo(out) }
                out.closeEntry()
            }
            repeat(6000) { index ->
                out.putNextEntry(ZipEntry("assets/resources/payload_$index.bin"))
                out.write(byteArrayOf(1, 2, 3, 4)); out.closeEntry()
            }
        } }
        val result = ArchivePreparer().prepare(source, "application.apk", root, device)
        assertEquals("dev.splitbox.fixture", result.metadata.packageName)
        assertEquals(1, result.apks.size)
        assertEquals(source.canonicalFile, result.apks.single().file.canonicalFile)
        assertFalse(File(root, "prepared/base.apk").exists())
    }

    @Test fun readsZip64EntryCountAboveClassicUnsignedShort() = workspace { root ->
        val file = File(root, "many.apk")
        ZipOutputStream(file.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("AndroidManifest.xml")); out.write(CoreFixtures.manifest()); out.closeEntry()
            repeat(65_535) { index ->
                out.putNextEntry(ZipEntry("res/raw/file_$index.bin")); out.closeEntry()
            }
        }
        val inspected = ApkInspector().inspect(file, "many.apk") { false }
        assertEquals("dev.test", inspected.metadata.packageName)
        assertEquals(file.length(), inspected.size)
    }

    @Test fun readsRealZip64OffsetsAndStoredEntrySizesPastEightGiB() = workspace { root ->
        val fixture = sparseZip(root)
        assertTrue(fixture.file.length() > 8L * 1024 * 1024 * 1024)
        openCheckedZip(fixture.file).use { zip ->
            val entries = checkedZipEntries(zip)
            assertEquals(2, entries.size)
            assertEquals(8_589_934_715L, entries.first().size)
            assertEquals(8_589_934_715L, entries.first().compressedSize)
            assertArrayEquals(fixture.apk, zip.getInputStream(entries.last()).use { it.readBytes() })
        }
    }

    @Test fun rejectsHugeExpandedEntryBeforeLeavingAnyPreparedFiles() = workspace { root ->
        val fixture = sparseZip(root)
        val stage = File(root, "stage").apply { mkdir() }
        reject { ArchivePreparer().prepare(fixture.file, "large.apks", stage, device, PrepareOptions(1024)) }
        assertFalse(File(stage, "prepared").exists())
        assertTrue(fixture.file.exists())
    }

    @Test fun rejectsMalformedZip64EndRecords() = workspace { root ->
        val mutations: List<(RandomAccessFile, SparseZip) -> Unit> = listOf(
            { f, z -> f.patch(z.locator, int(0)) }, // Нет locator.
            { f, z -> f.patch(z.locator + 4, int(1)) }, // Другой диск ZIP64 EOCD.
            { f, z -> f.patch(z.locator + 16, int(2)) }, // Многотомный архив.
            { f, z -> f.patch(z.locator + 8, long(Long.MAX_VALUE)) }, // Смещение за концом.
            { f, z -> f.patch(z.zip64End + 4, long(43)) }, // Короткая запись ZIP64 EOCD.
            { f, z -> f.patch(z.zip64End + 4, long(Long.MAX_VALUE)) }, // Переполнение длины.
            { f, z -> f.patch(z.zip64End + 16, int(1)) }, // Другой текущий диск.
            { f, z -> f.patch(z.zip64End + 24, long(1)) }, // Число записей на диске отличается.
            { f, z ->
                f.patch(z.zip64End + 24, long(1) + long(1)); f.patch(z.end + 8, short(1) + short(1))
            }, // Реальных записей больше заявленных.
            { f, z -> f.patch(z.zip64End + 24, long(-1) + long(-1)) }, // unsigned64 > Long.MAX_VALUE.
            { f, z -> f.patch(z.zip64End + 40, long(Long.MAX_VALUE)) }, // Переполнение размера CEN.
            { f, z -> f.patch(z.zip64End + 48, long(z.central + 1)) }, // Смещение CEN неточно.
            { f, z -> f.patch(z.end + 8, short(1) + short(1)) }, // Legacy/ZIP64 противоречат.
            { f, z -> f.patch(z.end + 16, int(0)) } // Legacy offset противоречит ZIP64.
        )
        mutations.forEachIndexed { index, mutate ->
            val fixture = sparseZip(File(root, "case$index").apply { mkdir() })
            RandomAccessFile(fixture.file, "rw").use { mutate(it, fixture) }
            reject { openCheckedZip(fixture.file).close() }
            fixture.file.delete()
        }
    }

    @Test fun rejectsMalformedZip64EntryMetadata() = workspace { root ->
        val mutations: List<(RandomAccessFile, SparseZip) -> Unit> = listOf(
            { f, z -> f.patch(z.central + 46 + "padding.bin".length, short(2)) }, // Обязательный extra64 отсутствует.
            { f, z -> f.patch(z.central + 46 + "padding.bin".length + 2, short(8)) }, // Нет compressed size.
            { f, z -> f.patch(z.central + 46 + "padding.bin".length + 4, long(-1)) }, // unsigned64 > Long.MAX_VALUE.
            { f, z -> f.patch(z.central + 46 + "padding.bin".length + 12, long(Long.MAX_VALUE)) }, // Данные вне архива.
            { f, z -> f.patch(z.secondCentral + 46 + "base.apk".length + 4, long(z.central)) }, // Local header внутри CEN.
            { f, z -> f.patch(z.secondCentral + 34, short(1)) }, // Entry начинается на другом диске.
            { f, z -> f.patch(z.secondCentral + 8, short(1)) }, // Зашифрованная запись.
            { f, z -> f.patch(z.secondCentral + 10, short(99)) } // Неизвестное сжатие.
        )
        mutations.forEachIndexed { index, mutate ->
            val fixture = sparseZip(File(root, "case$index").apply { mkdir() })
            RandomAccessFile(fixture.file, "rw").use { mutate(it, fixture) }
            reject { openCheckedZip(fixture.file).close() }
            fixture.file.delete()
        }
    }

    @Test fun rejectsDishonestClassicEntryCountBeforeOpeningZipFile() = workspace { root ->
        val file = CoreFixtures.zip(File(root, "dishonest.zip"), listOf("a" to byteArrayOf(), "b" to byteArrayOf()))
        RandomAccessFile(file, "rw").use { it.patch(it.length() - 14, short(1) + short(1)) }
        reject { openCheckedZip(file).close() }
    }

    @Test fun extractsSmallZip64ContainerWithSaturatedLegacyCounters() = workspace { root ->
        val apk = CoreFixtures.bytesApk()
        val source = forceZip64(CoreFixtures.zip(File(root, "small.apks"), listOf("base.apk" to apk)))
        val result = ArchivePreparer().prepare(source, source.name, File(root, "stage").apply { mkdir() }, device)
        assertEquals("dev.test", result.metadata.packageName)
        assertArrayEquals(apk, result.apks.single().file.readBytes())
    }

    @Test fun outerArchiveResourcesDoNotCountAsApkSplits() = workspace { root ->
        val source = CoreFixtures.zip(File(root, "resources.apks"),
            listOf("base.apk" to CoreFixtures.bytesApk()) + (1..4100).map { "assets/file$it" to byteArrayOf() })
        val result = ArchivePreparer().prepare(source, source.name, File(root, "stage").apply { mkdir() }, device)
        assertEquals(1, result.apks.size)
        assertTrue(File(root, "stage/prepared/assets/file4100").exists())
    }

    @Test fun tooManyApkSplitsAreRejectedBeforeExtraction() = workspace { root ->
        val source = CoreFixtures.zip(File(root, "too-many.apks"),
            (1..4097).map { "split$it.apk" to byteArrayOf(1) })
        val output = File(root, "out").apply { mkdir() }
        reject { SafeArchiveExtractor().extract(source, output, ByteBudget(Long.MAX_VALUE), { false }) { _, _ -> } }
        assertTrue(output.listFiles()!!.isEmpty())
    }

    @Test fun rejectsOversizedZipMetadataBeforeAllocatingIndex() = workspace { root ->
        val fixture = sparseZip(root)
        RandomAccessFile(fixture.file, "rw").use { file ->
            file.patch(fixture.end + 8, short(65535) + short(65535))
            file.patch(fixture.zip64End + 24, long(100_001) + long(100_001))
        }
        reject { openCheckedZip(fixture.file).close() }
        RandomAccessFile(fixture.file, "rw").use { file ->
            file.patch(fixture.zip64End + 24, long(2) + long(2))
            file.patch(fixture.end + 12, int(-1) + int(-1))
            file.patch(fixture.zip64End + 40, long(33L * 1024 * 1024) + long(fixture.zip64End - 33L * 1024 * 1024))
        }
        reject { openCheckedZip(fixture.file).close() }
    }

    @Test fun rejectsTruncationDuplicateAndTraversalInZip64() = workspace { root ->
        val valid = forceZip64(CoreFixtures.zip(File(root, "truncated.zip"), listOf("base.apk" to CoreFixtures.bytesApk())))
        RandomAccessFile(valid, "rw").use { it.setLength(it.length() - 1) }
        reject { openCheckedZip(valid).close() }
        val traversal = forceZip64(CoreFixtures.zip(File(root, "traversal.zip"), listOf("../x.apk" to byteArrayOf(1))))
        openCheckedZip(traversal).use { zip -> reject { checkedZipEntries(zip) } }
        val duplicate = forceZip64(CoreFixtures.zip(File(root, "duplicate.zip"), listOf("a.apk" to byteArrayOf(1), "b.apk" to byteArrayOf(2))))
        val bytes = duplicate.readBytes()
        val oldName = "b.apk".toByteArray()
        for (index in 0..bytes.size - oldName.size) {
            if (oldName.indices.all { bytes[index + it] == oldName[it] }) bytes[index] = 'a'.code.toByte()
        }
        duplicate.writeBytes(bytes)
        openCheckedZip(duplicate).use { zip -> reject { checkedZipEntries(zip) } }
    }

    @Test fun rejectsFileParentEvenWithAdjacentLexicographicSibling() = workspace { root ->
        val source = CoreFixtures.zip(File(root, "conflict.zip"), listOf("a" to byteArrayOf(),
            "a!" to byteArrayOf(), "a/b/file" to byteArrayOf()))
        openCheckedZip(source).use { zip -> reject { checkedZipEntries(zip) } }
    }

    @Test fun cancellationInterruptsPreflightAndEntryEnumeration() = workspace { root ->
        val source = CoreFixtures.zip(File(root, "cancel.zip"), (1..100).map { "file$it" to byteArrayOf() })
        var preflightChecks = 0
        assertThrows(CancellationException::class.java) { openCheckedZip(source) { ++preflightChecks > 10 }.close() }
        assertEquals(11, preflightChecks)
        var enumerationChecks = 0
        ZipFile(source).use { zip -> assertThrows(CancellationException::class.java) { checkedZipEntries(zip) { ++enumerationChecks > 10 } } }
        assertEquals(11, enumerationChecks)
        openCheckedZip(source).use { zip -> assertEquals(100, checkedZipEntries(zip).size) }
    }

    @Test fun preservesCallbackCancellationAndCleansPreparedFiles() = workspace { root ->
        val source = CoreFixtures.zip(File(root, "source.bin"), listOf("base.apk" to CoreFixtures.bytesApk()))
        val cancellation = CancellationException("Отменено владельцем операции")
        val actual = assertThrows(CancellationException::class.java) {
            ArchivePreparer().prepare(source, "package.apks", root, device,
                onProgress = { message, _ -> if (message.startsWith("Чтение APK")) throw cancellation })
        }
        assertSame(cancellation, actual)
        assertTrue(source.exists())
        assertFalse(File(root, "prepared").exists())
    }

    // Преобразование только завершающих записей сохраняет обычные local/CEN records и payload.
    private fun forceZip64(file: File): File {
        RandomAccessFile(file, "rw").use { out ->
            val end = out.length() - 22
            out.seek(end)
            val classic = ByteArray(22).also { out.readFully(it) }
            val buffer = ByteBuffer.wrap(classic).order(ByteOrder.LITTLE_ENDIAN)
            val count = buffer.getShort(10).toInt() and 65535
            val size = buffer.getInt(12).toLong() and 0xffffffffL
            val offset = buffer.getInt(16).toLong() and 0xffffffffL
            out.seek(end)
            out.write(int(0x06064b50) + long(44) + short(45) + short(45) + int(0) + int(0) +
                long(count.toLong()) + long(count.toLong()) + long(size) + long(offset))
            out.write(int(0x07064b50) + int(0) + long(end) + int(1))
            out.write(int(0x06054b50) + short(0) + short(0) + short(65535) + short(65535) +
                int(-1) + int(-1) + short(0))
        }
        return file
    }

    private data class SparseZip(val file: File, val apk: ByteArray, val central: Long, val secondCentral: Long,
        val zip64End: Long, val locator: Long, val end: Long)

    /** Настоящий single-disk ZIP64: 8 ГиБ+123 нулей в STORED entry и APK после него.
     * CRC нулей независимо рассчитан zlib.crc32 блоками 1 МиБ: 3684791464.
     * Hole экономит физическое место, все смещения и размеры на диске остаются 64-битными. */
    private fun sparseZip(root: File): SparseZip {
        val file = File(root, "sparse.apks")
        val apk = CoreFixtures.bytesApk()
        val paddingSize = 8_589_934_715L
        val paddingCrc = 3_684_791_464L
        val apkCrc = CRC32().apply { update(apk) }.value
        RandomAccessFile(file, "rw").use { out ->
            val paddingName = "padding.bin".toByteArray()
            val paddingExtra = short(1) + short(16) + long(paddingSize) + long(paddingSize)
            out.write(localHeader(paddingName, -1, paddingCrc, paddingExtra))
            out.seek(out.filePointer + paddingSize)
            val apkOffset = out.filePointer
            val apkName = "base.apk".toByteArray()
            out.write(localHeader(apkName, apk.size, apkCrc, byteArrayOf())); out.write(apk)
            val central = out.filePointer
            out.write(centralHeader(paddingName, -1, paddingCrc, 0, paddingExtra))
            val secondCentral = out.filePointer
            out.write(centralHeader(apkName, apk.size, apkCrc, -1,
                short(1) + short(8) + long(apkOffset)))
            val zip64End = out.filePointer
            val centralSize = zip64End - central
            out.write(int(0x06064b50) + long(44) + short(45) + short(45) + int(0) + int(0) +
                long(2) + long(2) + long(centralSize) + long(central))
            val locator = out.filePointer
            out.write(int(0x07064b50) + int(0) + long(zip64End) + int(1))
            val end = out.filePointer
            out.write(int(0x06054b50) + short(0) + short(0) + short(2) + short(2) +
                int(centralSize.toInt()) + int(-1) + short(0))
            return SparseZip(file, apk, central, secondCentral, zip64End, locator, end)
        }
    }

    private fun localHeader(name: ByteArray, size: Int, crc: Long, extra: ByteArray) =
        int(0x04034b50) + short(45) + short(0) + short(0) + short(0) + short(0) + int(crc.toInt()) +
            int(size) + int(size) + short(name.size) + short(extra.size) + name + extra

    private fun centralHeader(name: ByteArray, size: Int, crc: Long, offset: Int, extra: ByteArray) =
        int(0x02014b50) + short(45) + short(45) + short(0) + short(0) + short(0) + short(0) +
            int(crc.toInt()) + int(size) + int(size) + short(name.size) + short(extra.size) + short(0) +
            short(0) + short(0) + int(0) + int(offset) + name + extra

    private fun RandomAccessFile.patch(offset: Long, bytes: ByteArray) { seek(offset); write(bytes) }
    private fun short(value: Int) = byteArrayOf(value.toByte(), (value ushr 8).toByte())
    private fun int(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun long(value: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
}
