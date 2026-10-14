// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.splitbox.app.BuildConfig
import dev.splitbox.app.model.HistoryEntry
import dev.splitbox.app.ui.components.Eyebrow
import dev.splitbox.app.ui.components.Panel
import dev.splitbox.app.ui.components.displayName
import dev.splitbox.app.ui.components.formatTimestamp

@Composable
fun HistoryScreen(history: List<HistoryEntry>, onClearHistory: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf(HistoryStatusFilter.ALL) }
    var backend by rememberSaveable { mutableStateOf(HistoryBackendFilter.ALL) }
    var clearConfirmation by rememberSaveable { mutableStateOf(false) }
    var report by rememberSaveable { mutableStateOf<String?>(null) }
    val filtered = remember(history, query, status, backend) { filterHistory(history, query, status, backend) }
    val hasFilters = query.isNotEmpty() || status != HistoryStatusFilter.ALL || backend != HistoryBackendFilter.ALL
    fun resetFilters() {
        query = ""
        status = HistoryStatusFilter.ALL
        backend = HistoryBackendFilter.ALL
    }
    LazyColumn(modifier, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item("heading") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Eyebrow("НА ЭТОМ УСТРОЙСТВЕ")
                    Text("История\nустановок", style = MaterialTheme.typography.headlineLarge)
                    Text("Записей: ${history.size} · хранится локально", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { clearConfirmation = true }, enabled = history.isNotEmpty()) {
                    Icon(Icons.Rounded.DeleteOutline, "Очистить историю")
                }
            }
        }
        item("search") {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Найти приложение или файл") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Очистить поиск") }
                },
                shape = MaterialTheme.shapes.large,
            )
        }
        item("filters") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryFilterMenu("Результат", status.title, HistoryStatusFilter.entries, { it.title }) { status = it }
                HistoryFilterMenu("Способ установки", backend.title, HistoryBackendFilter.entries, { it.title }) { backend = it }
                Text("Показано ${filtered.size} из ${history.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (hasFilters) TextButton(onClick = ::resetFilters) { Text("Сбросить фильтры") }
            }
        }
        if (filtered.isEmpty()) {
            item("empty") {
                Panel(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().padding(top = 20.dp), contentAlignment = Alignment.Center) {
                        Icon(if (history.isEmpty()) Icons.Rounded.History else Icons.Rounded.Search, null, Modifier.size(54.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(if (history.isEmpty()) "Здесь начинается ваша история" else "Совпадений пока нет", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (history.isEmpty()) "Завершённые попытки установки появятся здесь. Сохранение истории можно отключить в настройках."
                        else "Измените поиск, результат или способ установки либо сбросьте фильтры.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(filtered, key = { it.id }) { entry ->
            Panel(Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(entry.appLabel.ifBlank { entry.packageName }, style = MaterialTheme.typography.titleLarge)
                        Text(formatTimestamp(entry.timestamp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(when (entry.success) { true -> Icons.Rounded.CheckCircle; false -> Icons.Rounded.ErrorOutline; null -> Icons.Rounded.History },
                        when (entry.success) { true -> "Успешно"; false -> "Не установлено"; null -> "Результат не подтверждён" },
                        Modifier.size(24.dp), tint = when (entry.success) { true -> MaterialTheme.colorScheme.primary;
                            false -> MaterialTheme.colorScheme.error; null -> MaterialTheme.colorScheme.onSurfaceVariant })
                }
                SelectionContainer { Text(entry.packageName, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                Text("${entry.versionName.ifBlank { "Версия не указана" }} · ${entry.backend.displayName()}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(entry.fileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer { Text(entry.message, style = MaterialTheme.typography.bodyMedium) }
                TextButton(onClick = { report = formatHistoryReport(entry, BuildConfig.VERSION_NAME) }) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp))
                    Text("Скопировать отчёт", Modifier.padding(start = 8.dp))
                }
            }
        }
    }
    report?.let { shownReport ->
        AlertDialog(
            onDismissRequest = { report = null },
            icon = { Icon(Icons.Rounded.ContentCopy, null) },
            title = { Text("Отчёт об установке") },
            text = {
                SelectionContainer {
                    Text(shownReport, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // Копируем тот же снимок текста, который пользователь прочитал в диалоге.
                    val copied = try {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        if (clipboard == null) false else {
                            clipboard.setPrimaryClip(ClipData.newPlainText("Отчёт SplitBox", shownReport))
                            val clip = clipboard.primaryClip
                            clip != null && clip.itemCount == 1 && clip.getItemAt(0).text?.toString() == shownReport
                        }
                    } catch (_: RuntimeException) { false }
                    Toast.makeText(context, if (copied) "Отчёт скопирован" else "Не удалось скопировать отчёт. Попробуйте ещё раз.", Toast.LENGTH_SHORT).show()
                    if (copied) report = null
                }) { Text("Скопировать") }
            },
            dismissButton = { TextButton(onClick = { report = null }) { Text("Закрыть") } },
        )
    }
    if (clearConfirmation) {
        AlertDialog(
            onDismissRequest = { clearConfirmation = false },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = { Text("Очистить историю?") },
            text = { Text("Все ${history.size} записей будут удалены с этого устройства. Установленные приложения и исходные файлы сохранятся.") },
            confirmButton = {
                TextButton(onClick = { clearConfirmation = false; onClearHistory() }) { Text("Очистить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { clearConfirmation = false }) { Text("Отмена") } },
        )
    }
}

/** Отдельные полноширинные меню не требуют горизонтальной прокрутки при крупном шрифте. */
@Composable
private fun <T> HistoryFilterMenu(label: String, selected: String, options: List<T>, title: (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                Text(selected, Modifier.weight(1f).padding(vertical = 4.dp))
                Icon(Icons.Rounded.ArrowDropDown, "Выбрать: $label")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.widthIn(max = 300.dp)) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(title(option)) }, onClick = { onSelect(option); expanded = false })
                }
            }
        }
    }
}
