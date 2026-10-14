// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SelectionValidatorTest {
    @Test fun lateOldValidationCannotEnableNewSelection() = runTest {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val validator = SelectionValidator(backgroundScope, StandardTestDispatcher(testScheduler)) { _, paths ->
            if ("old" in paths) withContext(NonCancellable) { started.complete(Unit); release.await() }
            if ("bad" in paths) error("Не выбран обязательный APK")
        }
        validator.submit(emptyList(), setOf("old"))
        started.await()
        val latest = validator.submit(emptyList(), setOf("bad"))
        runCurrent()
        assertEquals(latest, validator.state.value.revision)
        assertEquals("Не выбран обязательный APK", validator.state.value.error)
        release.complete(Unit)
        runCurrent()
        assertEquals(latest, validator.state.value.revision)
        assertEquals("Не выбран обязательный APK", validator.state.value.error)
    }
    @Test fun pendingIsImmediateAndClearInvalidatesInflightWork() = runTest {
        val release = CompletableDeferred<Unit>()
        val validator = SelectionValidator(backgroundScope, StandardTestDispatcher(testScheduler)) { _, _ -> release.await() }
        validator.submit(emptyList(), setOf("base"))
        assertTrue(validator.state.value.pending)
        validator.clear()
        release.complete(Unit); runCurrent()
        assertFalse(validator.state.value.pending)
        assertNull(validator.state.value.error)
    }
}
