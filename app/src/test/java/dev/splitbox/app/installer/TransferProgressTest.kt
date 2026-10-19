// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.installer

import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.CancellationException

class TransferProgressTest {
    @Test fun firstAndFinalUpdatesSurviveThrottleAndIntermediateValuesStayMonotonic() {
        var now = 0L
        val updates = mutableListOf<Pair<Long, Float?>>()
        val reporting = TransferProgress(9_663_676_417L, { now }) { bytes, fraction -> updates += bytes to fraction }
        reporting.report(1)
        reporting.report(65536)
        now = 100_000_000L
        reporting.report(8_589_934_592L)
        reporting.report(7)
        reporting.report(9_663_676_417L)
        reporting.report(9_663_676_417L)
        assertEquals(listOf(1L, 8_589_934_592L, 9_663_676_417L), updates.map { it.first })
        assertTrue(updates[1].second!! in 0.88f..0.90f)
        assertEquals(1f, updates.last().second)
    }
    @Test fun binderDeadlineExceedsWriterDeadlineForSameLargeFileWithoutOverflow() {
        listOf(1L, 9_663_676_417L, Long.MAX_VALUE).forEach { bytes ->
            assertTrue(TransferPolicy.binderTimeoutMs(bytes) > TransferPolicy.timeoutSeconds(bytes) * 1000L)
        }
        assertEquals(36985L, TransferPolicy.timeoutSeconds(9_663_676_417L))
        assertThrows(IllegalArgumentException::class.java) { TransferPolicy.timeoutSeconds(0) }
    }
    @Test fun progressStreamUsesLongCountersWithoutAllocatingPayload() {
        var remaining = 9_663_676_417L
        val source = object : InputStream() {
            override fun read(): Int = error("Побайтовое чтение не ожидалось")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining == 0L) return -1
                return minOf(length.toLong(), remaining).toInt().also { remaining -= it }
            }
        }
        var finalCount = 0L
        val buffer = ByteArray(65536)
        ProgressInputStream(source) { finalCount = it }.use { while (it.read(buffer) >= 0) {} }
        assertEquals(9_663_676_417L, finalCount)
    }
    @Test fun interruptionIsCheckedBeforeEveryRead() {
        val input = ProgressInputStream("abc".byteInputStream()) {}
        Thread.currentThread().interrupt()
        try { assertThrows(CancellationException::class.java) { input.read(ByteArray(4)) } }
        finally { Thread.interrupted() }
    }
}
