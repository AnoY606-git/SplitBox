// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class InterruptedSystemSessionTest {
    private fun session(state: String = "COMMITTED") = PendingInstall(42, "dev.example.game", "1", "Игра", "game.apks", "/cache/game",
        42, true, true, state = state)
    @Test fun unsealedCommittedSessionBecomesInterruptedWithoutAnotherCommit() = runTest {
        var ledger = SystemSessionLedger(session())
        val stored = ledger.current!!
        var abandoned = false
        recoverSystemSession(stored, { ledger.isCurrent(stored) }, { false }, { abandoned = true },
            { ledger = ledger.replace(it) }, { ledger = ledger.missingSession(42) }, { fail("Не должно быть подтверждения") })
        assertTrue(abandoned); assertEquals("FINISHED", ledger.current!!.state); assertEquals(false, ledger.current!!.success)
    }
    @Test fun sealedSessionIsNeverAbandonedIncludingStaleWritingState() = runTest {
        for (state in listOf("WRITING", "COMMITTED", "CONFIRMATION", "UNKNOWN")) {
            val stored = session(state)
            recoverSystemSession(stored, { true }, { true }, { fail("Запечатанная сессия не отменяется") },
                { fail("Результат ещё не известен") }, { fail("Сессия существует") }, {})
        }
    }
    @Test fun replacementDuringSessionQueryIsNotAbandonedOrOverwritten() = runTest {
        val stored = session()
        var current = true
        recoverSystemSession(stored, { current }, { current = false; false }, { fail("Сессия заменена") },
            { fail("Нельзя публиковать старый результат") }, { fail("Сессия заменена") }, {})
    }
    @Test fun callbackDuringAbandonKeepsItsFinalResult() = runTest {
        var ledger = SystemSessionLedger(session())
        val stored = ledger.current!!
        recoverSystemSession(stored, { ledger.isCurrent(stored) }, { false },
            { ledger = ledger.recordResult(42, true, "Подтверждено Android") }, { ledger = ledger.replace(it) }, {}, {})
        assertEquals(true, ledger.current!!.success)
    }
}
