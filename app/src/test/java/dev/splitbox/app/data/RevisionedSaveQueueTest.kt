// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RevisionedSaveQueueTest {
    @Test fun coalescesAndFlushesLatestWithoutWritingOnCaller() = runTest {
        val written = mutableListOf<Int>()
        val queue = RevisionedSaveQueue(backgroundScope, StandardTestDispatcher(testScheduler)) { value: Int -> written += value }
        repeat(100) { queue.submit(it) }
        assertTrue(written.isEmpty())
        queue.flush()
        assertEquals(listOf(99), written)
        assertFalse(queue.state.value.saving)
    }
    @Test fun failureIsVisibleAndRetryDoesNotLoseValue() = runTest {
        var failing = true
        val written = mutableListOf<Int>()
        val queue = RevisionedSaveQueue(backgroundScope, StandardTestDispatcher(testScheduler)) { value: Int ->
            if (failing) throw IOException("Недостаточно места")
            written += value
        }
        queue.submit(42)
        try { queue.flush(); fail("Ожидалась ошибка сохранения") } catch (_: IOException) { }
        assertNotNull(queue.state.value.error)
        failing = false
        queue.retry()
        queue.flush()
        assertEquals(listOf(42), written)
    }
    @Test fun invalidationWaitsForOwnedWriteBeforeDeletionAndIgnoresStaleCompletion() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val written = mutableListOf<Int>()
        val queue = RevisionedSaveQueue(backgroundScope, StandardTestDispatcher(testScheduler)) { value: Int ->
            if (value == 1) { started.complete(Unit); release.await() }
            written += value
        }
        queue.submit(1)
        started.await()
        queue.submit(2)
        val invalidation = async { queue.invalidateAndAwait() }
        runCurrent()
        assertFalse(invalidation.isCompleted)
        release.complete(Unit)
        invalidation.await()
        queue.submit(3)
        queue.flush()
        assertEquals(listOf(1, 3), written)
        assertNull(queue.state.value.error)
        assertFalse(queue.state.value.saving)
    }
    @Test fun newerValueSurvivesFailureOfOlderWrite() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val written = mutableListOf<Int>()
        val queue = RevisionedSaveQueue(backgroundScope, StandardTestDispatcher(testScheduler)) { value: Int ->
            if (value == 1) { started.complete(Unit); release.await(); throw IOException("Ошибка старой записи") }
            written += value
        }
        queue.submit(1); started.await(); queue.submit(2); release.complete(Unit)
        queue.flush()
        assertEquals(listOf(2), written)
        assertNull(queue.state.value.error)
    }
    @Test fun flushWaitsForActualDiskAcknowledgement() = runTest {
        val release = CompletableDeferred<Unit>()
        val queue = RevisionedSaveQueue(backgroundScope, StandardTestDispatcher(testScheduler)) { _: Int -> release.await() }
        queue.submit(1)
        val flush = async { queue.flush() }
        runCurrent()
        assertFalse(flush.isCompleted)
        release.complete(Unit)
        flush.await()
        assertEquals(queue.state.value.revision, queue.state.value.savedRevision)
    }
}
