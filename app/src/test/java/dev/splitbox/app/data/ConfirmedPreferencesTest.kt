// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class ConfirmedPreferencesTest {
    private class Preferences(private val committed: Boolean) {
        val calls = mutableListOf<String>()
        private val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, _ ->
            calls += method.name
            when (method.name) {
                "commit" -> committed
                "apply" -> null
                else -> proxy
            }
        } as SharedPreferences.Editor
        val value = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            check(method.name == "edit")
            calls += "edit"
            editor
        } as SharedPreferences
    }

    @Test fun confirmsExactlyOneSynchronousWriteAfterChanges() {
        val prefs = Preferences(true)
        var errorBuilt = false
        prefs.value.commitChecked({ errorBuilt = true; "Ошибка записи" }) { putString("session", "данные") }
        assertEquals(listOf("edit", "putString", "commit"), prefs.calls)
        assertFalse(errorBuilt)
    }

    @Test fun failedCommitIsReportedWithoutApplyOrRetry() {
        val prefs = Preferences(false)
        val error = assertThrows(IllegalStateException::class.java) {
            prefs.value.commitChecked({ "Не удалось сохранить журнал" }) { putString("session", "данные") }
        }
        assertEquals("Не удалось сохранить журнал", error.message)
        assertEquals(listOf("edit", "putString", "commit"), prefs.calls)
    }

    @Test fun failedChangesNeverCommitPartialEditor() {
        val prefs = Preferences(true)
        val failure = IllegalArgumentException("Ошибка сериализации")
        val error = assertThrows(IllegalArgumentException::class.java) {
            prefs.value.commitChecked({ "Ошибка записи" }) { throw failure }
        }
        assertSame(failure, error)
        assertEquals(listOf("edit"), prefs.calls)
    }
}
