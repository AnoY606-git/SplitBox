// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.ui

import dev.splitbox.app.model.BackendMode
import dev.splitbox.app.model.HistoryEntry
import org.junit.Assert.*
import org.junit.Test

class HistoryPresentationTest {
    private val unknown = HistoryEntry("unknown-id", 1720000000000, "Карты", "dev.maps", "2.7", "maps.apks", BackendMode.ROOT, null, "Ожидание ответа")
    private val failed = unknown.copy(id = "failed-id", success = false, message = "Нет места")
    private val system = failed.copy(id = "system-id", backend = BackendMode.SYSTEM)
    private val success = unknown.copy(id = "success-id", success = true, backend = BackendMode.SHIZUKU)
    private val history = listOf(unknown, failed, system, success)

    @Test fun queryStatusAndBackendAreAnIntersectionPreservingHistoryOrder() {
        assertEquals(listOf(failed), filterHistory(history, "  MAPS  ", HistoryStatusFilter.FAILURE, HistoryBackendFilter.ROOT))
        assertEquals(listOf(unknown, failed), filterHistory(history, "  карты ", HistoryStatusFilter.ALL, HistoryBackendFilter.ROOT))
        assertEquals(listOf(success), filterHistory(history, "shIZuku", HistoryStatusFilter.SUCCESS, HistoryBackendFilter.ALL))
        assertEquals(history, filterHistory(history, "   "))
    }

    @Test fun unknownIsNeutralAndNeverMatchesFailureOrSuccess() {
        assertEquals(listOf(unknown), filterHistory(history, status = HistoryStatusFilter.UNKNOWN))
        assertEquals(listOf(failed, system), filterHistory(history, status = HistoryStatusFilter.FAILURE))
        assertEquals(listOf(success), filterHistory(history, status = HistoryStatusFilter.SUCCESS))
        assertTrue(filterHistory(listOf(unknown), status = HistoryStatusFilter.FAILURE).isEmpty())
    }

    @Test fun textSearchStillFindsEveryExistingSearchField() {
        listOf("карты", "DEV.MAPS", "maps.apks", "2.7", "ответа", "ROOT").forEach { term ->
            assertEquals(listOf(unknown), filterHistory(listOf(unknown), term))
        }
        assertTrue(filterHistory(history, "missing").isEmpty())
        assertTrue(filterHistory(emptyList(), "maps", HistoryStatusFilter.FAILURE, HistoryBackendFilter.SYSTEM).isEmpty())
    }

    @Test fun reportContainsRecordFieldsVersionAndUnconfirmedOutcomeWithoutInventingSuccess() {
        val report = formatHistoryReport(unknown, "1.1-test")
        assertTrue(report.startsWith("SplitBox 1.1-test\n"))
        listOf("Карты", "dev.maps", "2.7", "maps.apks", "Root", "Ожидание ответа", "2024", "Результат не подтверждён").forEach { assertTrue("Missing $it", report.contains(it)) }
        assertFalse(report.contains(unknown.id))
        assertFalse(report.contains("Успешно"))
        assertNotEquals(report, formatHistoryReport(unknown.copy(timestamp = 0), "1.1-test"))
        assertTrue(formatHistoryReport(failed, "1.1-test").contains("Не установлено"))
        assertTrue(formatHistoryReport(success, "1.1-test").contains("Успешно"))
    }
}
