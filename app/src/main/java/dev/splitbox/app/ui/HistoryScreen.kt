// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.ui

import dev.splitbox.app.R
import androidx.compose.ui.res.stringResource
import dev.splitbox.core.message
import dev.splitbox.core.nestedMessage

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
import dev.splitbox.app.ui.LocalizedIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import dev.splitbox.app.ui.LocalizedText as Text
import androidx.compose.material3.Text as RawText
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
    val text_history_screen_splitbox_report = stringResource(R.string.history_screen_splitbox_report)
    val text_history_screen_report_copied = stringResource(R.string.history_screen_report_copied)
    val text_history_screen_could_not_copy_the_report_try_again = stringResource(R.string.history_screen_could_not_copy_the_report_try_again)

    val context = LocalContext.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    var query by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf(HistoryStatusFilter.ALL) }
    var backend by rememberSaveable { mutableStateOf(HistoryBackendFilter.ALL) }
    var clearConfirmation by rememberSaveable { mutableStateOf(false) }
    var reportEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    val filtered = remember(history, query, status, backend, configuration) { filterHistory(history, query, status, backend, context::resolveMessage) }
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
                    Eyebrow(stringResource(R.string.history_screen_on_this_device))
                    Text(stringResource(R.string.history_screen_installation_history), style = MaterialTheme.typography.headlineLarge)
                    Text(message("history_screen_1_entries_stored_locally", "Записей: %1\$s · хранится локально", history.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { clearConfirmation = true }, enabled = history.isNotEmpty()) {
                    Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.history_screen_clear_history))
                }
            }
        }
        item("search") {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.history_screen_find_an_app_or_file)) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, stringResource(R.string.history_screen_clear_search)) }
                },
                shape = MaterialTheme.shapes.large,
            )
        }
        item("filters") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryFilterMenu(stringResource(R.string.history_screen_result), status.title, HistoryStatusFilter.entries, { it.title }) { status = it }
                HistoryFilterMenu(stringResource(R.string.history_screen_installation_method), backend.title, HistoryBackendFilter.entries, { it.title }) { backend = it }
                Text(message("history_screen_showing_1_of_2", "Показано %1\$s из %2\$s", filtered.size, history.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (hasFilters) TextButton(onClick = ::resetFilters) { Text(stringResource(R.string.history_screen_reset_filters)) }
            }
        }
        if (filtered.isEmpty()) {
            item("empty") {
                Panel(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().padding(top = 20.dp), contentAlignment = Alignment.Center) {
                        Icon(if (history.isEmpty()) Icons.Rounded.History else Icons.Rounded.Search, null, Modifier.size(54.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(if (history.isEmpty()) stringResource(R.string.history_screen_your_history_starts_here) else stringResource(R.string.history_screen_no_matches_yet), style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (history.isEmpty()) stringResource(R.string.history_screen_completed_installation_attempts_will_appear_here_you)
                        else stringResource(R.string.history_screen_change_the_search_result_or_installation_method),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(filtered, key = { it.id }) { entry ->
            Panel(Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        RawText(entry.appLabel.ifBlank { entry.packageName }, style = MaterialTheme.typography.titleLarge)
                        Text(formatTimestamp(entry.timestamp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(when (entry.success) { true -> Icons.Rounded.CheckCircle; false -> Icons.Rounded.ErrorOutline; null -> Icons.Rounded.History },
                        when (entry.success) { true -> stringResource(R.string.history_presentation_successful); false -> stringResource(R.string.history_presentation_not_installed); null -> stringResource(R.string.split_box_view_model_result_not_confirmed) },
                        Modifier.size(24.dp), tint = when (entry.success) { true -> MaterialTheme.colorScheme.primary;
                            false -> MaterialTheme.colorScheme.error; null -> MaterialTheme.colorScheme.onSurfaceVariant })
                }
                SelectionContainer { RawText(entry.packageName, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                Text(message("history_screen_1_2", "%1\$s · %2\$s", entry.versionName.ifBlank { stringResource(R.string.history_screen_version_not_specified) }, nestedMessage(entry.backend.displayName())), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(entry.fileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer { Text(entry.message, style = MaterialTheme.typography.bodyMedium) }
                TextButton(onClick = { reportEntryId = entry.id }) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.history_screen_copy_report), Modifier.padding(start = 8.dp))
                }
            }
        }
    }
    history.firstOrNull { it.id == reportEntryId }?.let { entry ->
        val shownReport = localizedText(formatHistoryReport(entry, BuildConfig.VERSION_NAME, configuration.locales[0]))
        AlertDialog(
            onDismissRequest = { reportEntryId = null },
            icon = { Icon(Icons.Rounded.ContentCopy, null) },
            title = { Text(stringResource(R.string.history_screen_installation_report)) },
            text = {
                SelectionContainer {
                    RawText(shownReport, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // Копируем тот же снимок текста, который пользователь прочитал в диалоге.
                    val copied = try {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        if (clipboard == null) false else {
                            clipboard.setPrimaryClip(ClipData.newPlainText(context.resolveMessage(text_history_screen_splitbox_report), shownReport))
                            val clip = clipboard.primaryClip
                            clip != null && clip.itemCount == 1 && clip.getItemAt(0).text?.toString() == shownReport
                        }
                    } catch (_: RuntimeException) { false }
                    Toast.makeText(context, context.resolveMessage(if (copied) text_history_screen_report_copied else text_history_screen_could_not_copy_the_report_try_again), Toast.LENGTH_SHORT).show()
                    if (copied) reportEntryId = null
                }) { Text(stringResource(R.string.history_screen_copy)) }
            },
            dismissButton = { TextButton(onClick = { reportEntryId = null }) { Text(stringResource(R.string.history_screen_close)) } },
        )
    }
    if (clearConfirmation) {
        AlertDialog(
            onDismissRequest = { clearConfirmation = false },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = { Text(stringResource(R.string.history_screen_clear_history_2)) },
            text = { Text(message("history_screen_all_1_entries_will_be_deleted_from", "Все %1\$s записей будут удалены с этого устройства. Установленные приложения и исходные файлы сохранятся.", history.size)) },
            confirmButton = {
                TextButton(onClick = { clearConfirmation = false; onClearHistory() }) { Text(stringResource(R.string.history_screen_clear), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { clearConfirmation = false }) { Text(stringResource(R.string.history_screen_cancel)) } },
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
                Icon(Icons.Rounded.ArrowDropDown, message("history_screen_choose_1", "Выбрать: %1\$s", label))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.widthIn(max = 300.dp)) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(title(option)) }, onClick = { onSelect(option); expanded = false })
                }
            }
        }
    }
}
