// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.ui

import dev.splitbox.core.message
import dev.splitbox.core.nestedMessage

import java.util.Locale
import dev.splitbox.app.model.BackendMode
import dev.splitbox.app.model.HistoryEntry
import dev.splitbox.app.ui.components.displayName
import dev.splitbox.app.ui.components.formatTimestamp

internal enum class HistoryStatusFilter(val title: String) {
    ALL(message("history_presentation_all_results", "Все результаты")), SUCCESS(message("history_presentation_successful", "Успешно")), FAILURE(message("history_presentation_not_installed", "Не установлено")), UNKNOWN(message("split_box_view_model_result_not_confirmed", "Результат не подтверждён")),
}

internal enum class HistoryBackendFilter(val mode: BackendMode?, val title: String) {
    ALL(null, message("history_presentation_all_methods", "Все способы")), SYSTEM(BackendMode.SYSTEM, message("history_presentation_system", "Системный")),
    ROOT(BackendMode.ROOT, "Root"), SHIZUKU(BackendMode.SHIZUKU, "Shizuku"),
}

/** Фильтры меняют только представление, сохраняя порядок и исходные записи. */
internal fun filterHistory(
    history: List<HistoryEntry>,
    query: String = "",
    status: HistoryStatusFilter = HistoryStatusFilter.ALL,
    backend: HistoryBackendFilter = HistoryBackendFilter.ALL,
    render: (String) -> String = dev.splitbox.core.MessageEnvelope::diagnostic,
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
            (term.isEmpty() || listOf(entry.appLabel, entry.packageName, render(entry.fileName),
                entry.versionName, render(entry.message), render(entry.backend.displayName())).any { it.contains(term, ignoreCase = true) })
    }
}

/** Только поля сохранённой записи и версия SplitBox: никаких дополнительных источников. */
internal fun formatHistoryReport(entry: HistoryEntry, version: String, locale: Locale = Locale.getDefault()): String = buildString {
    appendLine("SplitBox $version")
    appendLine(message("history_presentation_date", "Дата: %1\$s", formatTimestamp(entry.timestamp, locale)))
    appendLine(message("history_presentation_app", "Приложение: %1\$s", entry.appLabel))
    appendLine(message("history_presentation_package", "Пакет: %1\$s", entry.packageName))
    appendLine(message("history_presentation_version", "Версия: %1\$s", entry.versionName))
    appendLine(message("history_presentation_source_file", "Исходный файл: %1\$s", nestedMessage(entry.fileName)))
    appendLine(message("history_presentation_installation_method_1", "Способ установки: %1\$s", nestedMessage(entry.backend.displayName())))
    val result = when (entry.success) {
        true -> HistoryStatusFilter.SUCCESS.title
        false -> HistoryStatusFilter.FAILURE.title
        null -> HistoryStatusFilter.UNKNOWN.title
    }
    appendLine(message("history_presentation_result_1", "Результат: %1\$s", nestedMessage(result)))
    append(message("history_presentation_message_1", "Сообщение: %1\$s", nestedMessage(entry.message)))
}
