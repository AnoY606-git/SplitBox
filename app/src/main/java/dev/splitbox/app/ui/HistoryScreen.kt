// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.ui

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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.splitbox.app.model.HistoryEntry
import dev.splitbox.app.ui.components.Eyebrow
import dev.splitbox.app.ui.components.Panel
import dev.splitbox.app.ui.components.displayName
import dev.splitbox.app.ui.components.formatTimestamp

@Composable
fun HistoryScreen(history: List<HistoryEntry>, onClearHistory: () -> Unit, modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    var clearConfirmation by rememberSaveable { mutableStateOf(false) }
    val filtered = remember(history, query) {
        val term = query.trim()
        history.filter { entry ->
            term.isEmpty() || listOf(entry.appLabel, entry.packageName, entry.fileName, entry.versionName, entry.message, entry.backend.displayName()).any { it.contains(term, ignoreCase = true) }
        }
    }
    LazyColumn(modifier, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item("heading") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Eyebrow("НА ЭТОМ УСТРОЙСТВЕ")
                    Text("История\nустановок", style = MaterialTheme.typography.headlineLarge)
                    Text("${history.size} записей · хранится локально", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        if (filtered.isEmpty()) {
            item("empty") {
                Panel(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().padding(top = 20.dp), contentAlignment = Alignment.Center) {
                        Icon(if (query.isBlank()) Icons.Rounded.History else Icons.Rounded.Search, null, Modifier.size(54.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(if (query.isBlank()) "Здесь начинается ваша история" else "Совпадений пока нет", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (query.isBlank()) "Завершённые попытки установки появятся здесь. Сохранение истории можно отключить в настройках."
                        else "Попробуйте название приложения, имя пакета или исходного файла.",
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
            }
        }
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
