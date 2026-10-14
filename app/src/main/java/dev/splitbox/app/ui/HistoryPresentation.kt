// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.ui

import dev.splitbox.app.model.BackendMode
import dev.splitbox.app.model.HistoryEntry
import dev.splitbox.app.ui.components.displayName
import dev.splitbox.app.ui.components.formatTimestamp

internal enum class HistoryStatusFilter(val title: String) {
    ALL("Все результаты"), SUCCESS("Успешно"), FAILURE("Не установлено"), UNKNOWN("Результат не подтверждён"),
}

internal enum class HistoryBackendFilter(val mode: BackendMode?, val title: String) {
    ALL(null, "Все способы"), SYSTEM(BackendMode.SYSTEM, "Системный"),
    ROOT(BackendMode.ROOT, "Root"), SHIZUKU(BackendMode.SHIZUKU, "Shizuku"),
}

/** Фильтры меняют только представление, сохраняя порядок и исходные записи. */
internal fun filterHistory(
    history: List<HistoryEntry>,
    query: String = "",
    status: HistoryStatusFilter = HistoryStatusFilter.ALL,
    backend: HistoryBackendFilter = HistoryBackendFilter.ALL,
): List<HistoryEntry> {
    val term = query.trim()
    return history.filter { entry ->
        val matchesStatus = when (status) {
            HistoryStatusFilter.ALL -> true
            HistoryStatusFilter.SUCCESS -> entry.success == true
            HistoryStatusFilter.FAILURE -> entry.success == false
            HistoryStatusFilter.UNKNOWN -> entry.success == null
        }
        matchesStatus && (backend.mode == null || entry.backend == backend.mode) &&
            (term.isEmpty() || listOf(entry.appLabel, entry.packageName, entry.fileName,
                entry.versionName, entry.message, entry.backend.displayName()).any { it.contains(term, ignoreCase = true) })
    }
}

/** Только поля сохранённой записи и версия SplitBox: никаких дополнительных источников. */
internal fun formatHistoryReport(entry: HistoryEntry, version: String): String = buildString {
    appendLine("SplitBox $version")
    appendLine("Дата: ${formatTimestamp(entry.timestamp)}")
    appendLine("Приложение: ${entry.appLabel}")
    appendLine("Пакет: ${entry.packageName}")
    appendLine("Версия: ${entry.versionName}")
    appendLine("Исходный файл: ${entry.fileName}")
    appendLine("Способ установки: ${entry.backend.displayName()}")
    val result = when (entry.success) {
        true -> HistoryStatusFilter.SUCCESS.title
        false -> HistoryStatusFilter.FAILURE.title
        null -> HistoryStatusFilter.UNKNOWN.title
    }
    appendLine("Результат: $result")
    append("Сообщение: ${entry.message}")
}
