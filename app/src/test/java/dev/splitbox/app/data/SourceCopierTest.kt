// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SourceCopierTest {
    @Test fun changingDiskSpaceStopsBeforeWritingTheNextChunk() {
        val output = ByteArrayOutputStream()
        var checks = 0
        assertThrows(IOException::class.java) {
            SourceCopier.copy(ByteArray(65537).inputStream(), output, Long.MAX_VALUE, false, { false }, {}) { bytes ->
                PackageSizePolicy.requireSpace(bytes, if (checks++ == 0) 67_174_400L else 67_108_864L)
            }
        }
        assertEquals(65536, output.size())
    }
    @Test fun sourceOverEightGiBStreamsWithoutAllocatingIt() {
        var remaining = 9_663_676_417L
        val input = object : java.io.InputStream() {
            override fun read(): Int = error("Побайтовое чтение не ожидалось")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining == 0L) return -1
                return minOf(length.toLong(), remaining).toInt().also { remaining -= it }
            }
        }
        val result = SourceCopier.copy(input, java.io.OutputStream.nullOutputStream(), Long.MAX_VALUE, false, { false }, {})
        assertEquals(9_663_676_417L, result.bytes)
    }
    @Test fun digestMatchesKnownVectorAcrossReadBoundaries() {
        listOf(1, 2, 3, 65536).forEach { chunk ->
            val source = object : ByteArrayInputStream("abc".toByteArray()) {
                override fun read(buffer: ByteArray, offset: Int, length: Int) = super.read(buffer, offset, minOf(length, chunk))
            }
            val output = ByteArrayOutputStream()
            val result = SourceCopier.copy(source, output, 3, true, { false }, {})
            assertEquals(3L, result.bytes)
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", result.sha256)
            assertArrayEquals("abc".toByteArray(), output.toByteArray())
        }
    }
    @Test fun streamsMultipleBuffersAndReportsOnlyWrittenBytesWithoutDigest() {
        val bytes = ByteArray(65537) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        val result = SourceCopier.copy(bytes.inputStream(), output, 65537, false, { false }, progress::add)
        assertArrayEquals(bytes, output.toByteArray())
        assertEquals(listOf(65536L, 65537L), progress)
        assertEquals(SourceCopyResult(65537L, null), result)
    }
    @Test fun rejectsOverLimitBeforeWritingOffendingChunk() {
        val output = ByteArrayOutputStream()
        assertThrows(IOException::class.java) {
            SourceCopier.copy(ByteArray(65537).inputStream(), output, 65536, true, { false }, {})
        }
        assertEquals(65536, output.size())
    }
    @Test fun rejectsEmptyInputEvenWithUnknownProviderSize() {
        assertThrows(IOException::class.java) {
            SourceCopier.copy(ByteArray(0).inputStream(), ByteArrayOutputStream(), 100, true, { false }, {})
        }
    }
    @Test fun cancellationBeforeReadDoesNotWrite() {
        val output = ByteArrayOutputStream()
        assertThrows(CancellationException::class.java) {
            SourceCopier.copy("abc".byteInputStream(), output, 3, true, { true }, {})
        }
        assertEquals(0, output.size())
    }
    @Test fun cancellationTriggeredByReadDoesNotWriteThatChunk() {
        var cancelled = false
        val input = object : ByteArrayInputStream("abc".toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                return super.read(buffer, offset, length).also { cancelled = true }
            }
        }
        val output = ByteArrayOutputStream()
        assertThrows(CancellationException::class.java) {
            SourceCopier.copy(input, output, 3, true, { cancelled }, {})
        }
        assertEquals(0, output.size())
    }
    @Test fun zeroLengthReadDoesNotEmitProgressOrFinishCopy() {
        val input = object : ByteArrayInputStream("abc".toByteArray()) {
            var first = true
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (first) { first = false; return 0 }
                return super.read(buffer, offset, length)
            }
        }
        val progress = mutableListOf<Long>()
        assertEquals(3L, SourceCopier.copy(input, ByteArrayOutputStream(), 3, false, { false }, progress::add).bytes)
        assertEquals(listOf(3L), progress)
    }
}
