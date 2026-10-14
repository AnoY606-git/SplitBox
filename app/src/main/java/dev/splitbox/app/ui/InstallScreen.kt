// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.splitbox.app.inspector.AndroidInstalledPackageReader
import dev.splitbox.app.inspector.InstalledPackageReader
import dev.splitbox.app.model.AccessState
import dev.splitbox.app.model.AppSettings
import dev.splitbox.app.model.BackendMode
import dev.splitbox.app.model.OperationPhase
import dev.splitbox.app.model.UiState
import dev.splitbox.app.ui.components.Eyebrow
import dev.splitbox.app.ui.components.Panel
import dev.splitbox.app.ui.components.SectionHeading
import dev.splitbox.app.ui.components.SplitBoxMark
import dev.splitbox.app.ui.components.StatusPill
import dev.splitbox.app.ui.components.displayName
import dev.splitbox.app.ui.components.formatBytes
import dev.splitbox.core.ApkEntry

@Composable
fun InstallScreen(
    ui: UiState,
    settings: AppSettings,
    access: AccessState,
    onChooseFile: () -> Unit,
    onChooseApks: () -> Unit = onChooseFile,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
    onToggleSplit: (String) -> Unit,
    onOpenApp: (String) -> Unit,
    onExportObb: (String) -> Unit,
    onSettings: () -> Unit,
    onCheckStatus: () -> Unit,
    modifier: Modifier = Modifier,
    onRetrySave: () -> Unit = {},
    installedPackageReader: InstalledPackageReader? = null,
) {
    val packages = LocalContext.current.packageManager
    val inspectorReader = installedPackageReader ?: remember(packages) { AndroidInstalledPackageReader(packages) }
    val busy = ui.phase.isBusy()
    val prepared = ui.prepared
    val workDir = prepared?.workDir?.absolutePath
    var filtersWorkDir by rememberSaveable { mutableStateOf(workDir) }
    var selectedOnly by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    // Сохраняем ключ вместе с фильтрами: новый пакет мог появиться на другой вкладке.
    if (filtersWorkDir != workDir) {
        filtersWorkDir = workDir
        selectedOnly = false
        query = ""
    }
    val groups = remember(prepared, ui.selectedPaths, selectedOnly, query) {
        groupSplits(prepared?.apks.orEmpty(), ui.selectedPaths, selectedOnly, query)
    }
    val selectedEntries = remember(prepared, ui.selectedPaths) {
        prepared?.apks.orEmpty().filter { it.archivePath in ui.selectedPaths }
    }
    Column(modifier) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (ui.phase == OperationPhase.IDLE && prepared == null) {
                item("hero") { InstallHero() }
            }
            if (ui.phase == OperationPhase.IDLE) {
                item("picker") { FilePickerCard(onChooseFile, onChooseApks) }
            }
            item("backend") {
                Surface(onClick = onSettings, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Способ установки", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Icon(Icons.Rounded.ChevronRight, "Изменить способ установки")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatusPill(settings.backend.displayName(), Icons.Rounded.Shield)
                            val status = when (settings.backend) {
                                BackendMode.SYSTEM -> "Подтверждение через Android"
                                BackendMode.ROOT -> access.rootStatus
                                BackendMode.SHIZUKU -> access.shizukuStatus
                            }
                            Text(status, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (ui.phase == OperationPhase.IDLE) {
                item("steps") { HowItWorks() }
            } else if (busy) {
                item("progress") { OperationPanel(ui, onCancel) }
            } else if (ui.phase == OperationPhase.SUCCESS || ui.phase == OperationPhase.ERROR) {
                item("result") { ResultPanel(ui, onOpenApp, onReset, onChooseFile) }
            } else if (ui.phase == OperationPhase.UNKNOWN) {
                item("unknown") { UnknownResultPanel(ui, onCheckStatus, onOpenApp, onChooseFile) }
            }

            if (prepared != null) {
                item("package") {
                    Panel(Modifier.fillMaxWidth()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Android, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Eyebrow("СОДЕРЖИМОЕ ПАКЕТА")
                                Text(ui.appLabel ?: prepared.metadata.packageName, style = MaterialTheme.typography.titleLarge)
                                Text("${prepared.metadata.versionName.ifBlank { "Без названия версии" }} · код ${prepared.metadata.versionCode}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        SelectionContainer {
                            Text(prepared.metadata.packageName, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        PackageInspectorButton(prepared, ui.selectedPaths, ui.appLabel,
                            ready = ui.phase == OperationPhase.READY, reader = inspectorReader)
                        DetailRow("Файл", prepared.sourceName)
                        DetailRow("Выбрано APK", "${ui.selectedPaths.size} из ${prepared.apks.size}")
                        DetailRow("Размер установки", formatBytes(prepared.apks.filter { it.archivePath in ui.selectedPaths }.sumOf { it.size }))
                        DetailRow("Android SDK", "от ${prepared.metadata.minSdk} · целевой ${prepared.metadata.targetSdk}")
                        if (ui.sha256 != null) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("SHA-256 исходного файла", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                SelectionContainer { Text(ui.sha256, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                            }
                        } else if (!settings.computeSha256) {
                            Text("Расчёт SHA-256 отключён в настройках", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (settings.verifySignatures) Icons.Rounded.Verified else Icons.Rounded.Security, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(
                                if (settings.verifySignatures) "Дополнительная проверка подписей включена" else "Дополнительная проверка подписей отключена. Android проверит подписи при установке.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                prepared.warnings.forEachIndexed { index, warning ->
                    item("warning_$index") { Notice(warning) }
                }
                if (prepared.obbFiles.isNotEmpty()) {
                    item("obb") {
                        Panel(Modifier.fillMaxWidth()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.FolderZip, null, tint = MaterialTheme.colorScheme.tertiary)
                                Column(Modifier.weight(1f)) {
                                    Text("Данные OBB", style = MaterialTheme.typography.titleMedium)
                                    Text("${prepared.obbFiles.size} файлов · ${formatBytes(prepared.obbFiles.sumOf { it.size })}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text(
                                if (settings.installObb && settings.backend != BackendMode.SYSTEM) "Перенос в Android/obb включён. Результат переноса будет показан отдельно от установки приложения."
                                else "Системный установщик не переносит OBB. Экспортируйте данные в ZIP; доступ к Android/obb зависит от ограничений вашего устройства.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = { onExportObb("${prepared.metadata.packageName}-obb.zip") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Rounded.FolderZip, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Экспортировать OBB")
                            }
                        }
                    }
                }
                if (ui.phase == OperationPhase.READY) {
                    ui.selectionSaveError?.let { error ->
                        item("save_error") {
                            Panel(Modifier.fillMaxWidth()) {
                                Text("Выбор не сохранён", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                                SelectionContainer { Text(error, style = MaterialTheme.typography.bodyMedium) }
                                Text("Установка станет доступна после успешного сохранения текущего выбора.", style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = onRetrySave, enabled = !ui.savingSelection, modifier = Modifier.fillMaxWidth()) {
                                    Text("Повторить сохранение")
                                }
                            }
                        }
                    }
                    ui.selectionError?.let { error -> item("selection_error") { Notice(error) } }
                    item("choose_other") {
                        TextButton(onClick = onChooseFile, modifier = Modifier.fillMaxWidth()) { Text("Выбрать другой файл") }
                    }
                }
                item("split_heading") {
                    SectionHeading("Части приложения", "Базовый APK и обязательные модули защищены. Совместимые дополнительные части можно отключить.")
                }
                item("split_filter") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = query, onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("Найти часть приложения") },
                            leadingIcon = { Icon(Icons.Rounded.Search, null) },
                            trailingIcon = {
                                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Очистить поиск") }
                            },
                            shape = MaterialTheme.shapes.large,
                        )
                        FilterChip(selected = selectedOnly, onClick = { selectedOnly = !selectedOnly }, label = { Text("Только выбранные") })
                        if (selectedOnly) Text("Обязательные и заблокированные части показаны, если совпадают с поиском.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Показано ${groups.sumOf { it.entries.size }} из ${prepared.apks.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (query.isNotEmpty() || selectedOnly) {
                            TextButton(onClick = { query = ""; selectedOnly = false }) { Text("Сбросить фильтры") }
                        }
                    }
                }
                if (groups.isEmpty()) {
                    item("split_empty") {
                        Panel(Modifier.fillMaxWidth()) {
                            Text("Подходящих частей нет", style = MaterialTheme.typography.titleMedium)
                            Text("Измените запрос или сбросьте фильтры. Выбор частей для установки сохранён.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                groups.forEach { group ->
                    item("group_${group.kind}", contentType = "group_heading") {
                        Text("${group.kind.title} · ${group.entries.size}", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    }
                    items(group.entries, key = { "split_${it.archivePath}" }, contentType = { "split" }) { entry ->
                        SplitRow(entry, entry.archivePath in ui.selectedPaths, editable = ui.phase == OperationPhase.READY, onToggle = { onToggleSplit(entry.archivePath) })
                    }
                }
            } else if (ui.phase == OperationPhase.READY) {
                item("choose_again") { FilePickerCard(onChooseFile, onChooseApks) }
            }
            item("privacy") {
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.CloudOff, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Локально на устройстве. Без загрузки в облако.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (prepared != null && ui.phase == OperationPhase.READY) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 4.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Выбрано ${selectedEntries.size} из ${prepared.apks.size} APK · ${formatBytes(selectedEntries.sumOf { it.size })}", style = MaterialTheme.typography.bodyMedium)
                    val status = when {
                        ui.savingSelection -> "Сохраняем выбор…"
                        ui.selectionSaveError != null -> "Не удалось сохранить выбор"
                        ui.selectionValidationPending -> "Проверяем совместимость…"
                        ui.selectionError != null -> "Проверьте выбранные части"
                        ui.selectedPaths.isEmpty() -> "Выберите части для установки"
                        else -> null
                    }
                    status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (ui.selectionSaveError != null) {
                        Button(onClick = onRetrySave, enabled = !ui.savingSelection, modifier = Modifier.fillMaxWidth()) {
                            Text("Повторить сохранение", Modifier.padding(vertical = 6.dp))
                        }
                    } else {
                        Button(onClick = onInstall, enabled = canInstallSelection(ui), modifier = Modifier.fillMaxWidth()) {
                            Text("Установить приложение", Modifier.padding(vertical = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstallHero() {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .62f), MaterialTheme.colorScheme.surfaceContainer))).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("APK и части приложения", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                SplitBoxMark(Modifier.size(36.dp), expanded = true)
            }
            Text("Выберите файл и проверьте состав перед установкой.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FilePickerCard(onChooseFile: () -> Unit, onChooseApks: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(onClick = onChooseFile, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.FileOpen, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Выбрать файл", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("APK · APKS · XAPK · APKM · ZIP", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("Откроется системный проводник", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .75f))
                }
            }
        }
        FilledTonalButton(onClick = onChooseApks, modifier = Modifier.fillMaxWidth()) { Text("Выбрать несколько APK") }
        Text("Все APK должны относиться к одному приложению", modifier = Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HowItWorks() {
    Panel(Modifier.fillMaxWidth()) {
        SectionHeading("Три простых шага")
        listOf("Выберите" to "APK или архив с частями приложения", "Проверьте" to "Версию, совместимость и выбранные части", "Установите" to "Явно выбранным способом установки").forEachIndexed { index, (title, subtitle) ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun OperationPanel(ui: UiState, onCancel: () -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 3.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(ui.title, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleLarge)
                if (ui.detail.isNotBlank()) Text(ui.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val progress = if (ui.phase == OperationPhase.COPYING) copyProgressFraction(ui.copiedBytes, ui.sourceBytes) else ui.progress
        if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (ui.phase == OperationPhase.COPYING) {
            val total = ui.sourceBytes?.takeIf { it > 0 }?.let { " из ${formatBytes(it)}" }.orEmpty()
            Text("Скопировано ${formatBytes(ui.copiedBytes.coerceAtLeast(0))}$total", style = MaterialTheme.typography.bodyMedium)
        }
        if (ui.canCancel) {
            TextButton(onClick = onCancel) { Text("Отменить операцию") }
        } else when (ui.phase) {
            OperationPhase.WAITING_USER -> Text("Подтвердите действие в системном диалоге Android. Отказ можно выбрать в этом же диалоге.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Text("Дождитесь ответа установщика. Повторный запуск недоступен.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UnknownResultPanel(ui: UiState, onCheckStatus: () -> Unit, onOpenApp: (String) -> Unit, onChooseFile: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(22.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.AutoMirrored.Rounded.HelpOutline, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Результат не подтверждён", style = MaterialTheme.typography.headlineSmall)
            SelectionContainer {
                Text(
                    ui.detail.ifBlank { "Android пока не подтвердил результат этой установки. Приложение могло быть установлено; проверьте его запуск или запросите статус повторно." },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onCheckStatus, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Проверить статус")
            }
            ui.pendingPackageName?.let { packageName ->
                OutlinedButton(onClick = { onOpenApp(packageName) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Открыть приложение")
                }
            }
            TextButton(onClick = onChooseFile, modifier = Modifier.fillMaxWidth()) { Text("Выбрать новый файл") }
        }
    }
}

@Composable
private fun ResultPanel(ui: UiState, onOpenApp: (String) -> Unit, onReset: () -> Unit, onChooseFile: () -> Unit) {
    val success = ui.result?.success == true && ui.phase == OperationPhase.SUCCESS
    val result = ui.result
    Surface(shape = RoundedCornerShape(24.dp), color = if (success) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(22.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(if (success) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null, Modifier.size(40.dp), tint = if (success) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer)
            Text(ui.title, style = MaterialTheme.typography.headlineSmall, color = if (success) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer)
            SelectionContainer {
                Text(result?.message ?: ui.detail.ifBlank { "Результат операции недоступен" }, style = MaterialTheme.typography.bodyMedium, color = if (success) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer)
            }
            result?.obbWarning?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
            if (success && result?.packageName != null) {
                Button(onClick = { onOpenApp(result.packageName) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Открыть приложение")
                }
            }
            FilledTonalButton(onClick = onChooseFile, modifier = Modifier.fillMaxWidth()) { Text("Выбрать следующий файл") }
            TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("На главный экран") }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Notice(message: String) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.WarningAmber, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

@Composable
private fun SplitRow(entry: ApkEntry, selected: Boolean, editable: Boolean, onToggle: () -> Unit) {
    // Ядро блокирует обязательные и несовместимые части. Начальный выбор задаёт только
    // предпочтение; совместимые альтернативы доступны для изменения и проверяются всем набором.
    val canToggle = editable && !entry.locked
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.fillMaxWidth().toggleable(value = selected, enabled = canToggle, role = Role.Checkbox, onValueChange = { onToggle() }).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = selected, enabled = canToggle, onCheckedChange = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.metadata.splitName ?: "Базовый APK", style = MaterialTheme.typography.titleMedium)
                Text(entry.archivePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(listOf(formatBytes(entry.size), entry.selectionReason).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (entry.locked) Icon(Icons.Rounded.Lock, if (entry.metadata.splitName == null || entry.metadata.isFeatureSplit) "Обязательная часть" else "Исключено проверкой совместимости", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
