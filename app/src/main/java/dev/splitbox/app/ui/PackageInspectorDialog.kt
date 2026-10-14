// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.splitbox.app.inspector.*
import dev.splitbox.core.PermissionDeclaration
import dev.splitbox.core.PreparedPackage
import kotlinx.coroutines.Dispatchers

/** При смене пакета, выбора или фазы диалог закрывается вместе со своим запросом. */
@Composable
fun PackageInspectorButton(prepared: PreparedPackage, selected: Set<String>, label: String?, ready: Boolean,
    reader: InstalledPackageReader) {
    var opened by remember(prepared, selected, ready) { mutableStateOf(false) }
    if (ready) {
        TextButton(onClick = { opened = true }) { Text("Сведения о пакете") }
        if (opened) PackageInspectorDialog(prepared, selected, label, reader, onDismiss = { opened = false })
    }
}

@Composable
private fun PackageInspectorDialog(prepared: PreparedPackage, selected: Set<String>, label: String?,
    reader: InstalledPackageReader, onDismiss: () -> Unit) {
    var inspection by remember(prepared, selected, reader) { mutableStateOf<PackageInspection?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(prepared, selected, reader) {
        // Повторное открытие запускает новую проверку. Отмена эффекта не публикует старый ответ.
        inspection = loadPackageInspection(prepared, selected.toSet(), reader, Dispatchers.IO)
    }
    val visiblePermissions = remember(inspection, query) { inspection?.permissions?.search(query).orEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сведения о пакете") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item("identity") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label ?: prepared.metadata.packageName, style = MaterialTheme.typography.titleMedium)
                        SelectionContainer { Text(prepared.metadata.packageName, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                item("comparison_heading") {
                    HorizontalDivider()
                    Text("Сравнение версий", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                }
                val details = inspection
                if (details == null) {
                    item("loading") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator()
                            Text("Проверяем установленную версию и выбранные APK…")
                        }
                    }
                } else {
                    item("comparison") { VersionComparisonBlock(details.comparison) }
                    item("permissions_heading") {
                        HorizontalDivider()
                        Text("Запрашиваемые разрешения", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                        Text("Декларации только выбранных APK. Это не сведения о выданных правах: Android может не применять часть деклараций из-за ограничений SDK.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (!details.permissions.complete) {
                        item("incomplete") {
                            Text("Сведения неполные: для части выбранных APK данные о разрешениях отсутствуют или не удалось их прочитать. Подготовьте пакет заново для повторного чтения.",
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    item("search") {
                        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), label = { Text("Найти разрешение по имени") })
                        Text("Показано ${visiblePermissions.size} из ${details.permissions.permissions.size}",
                            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    if (visiblePermissions.isEmpty()) {
                        item("empty") {
                            Text(when {
                                query.isNotBlank() -> "По этому запросу разрешения не найдены"
                                details.permissions.complete -> "В выбранных APK нет деклараций разрешений"
                                else -> "Доступных деклараций разрешений нет"
                            })
                        }
                    }
                    items(visiblePermissions, key = { it.name }) { group ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SelectionContainer { Text(group.name, style = MaterialTheme.typography.bodyMedium) }
                            Text(group.declarations.joinToString("; ") { permissionSdkRange(it) },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
private fun VersionComparisonBlock(comparison: VersionComparison) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(when (comparison.relation) {
            VersionRelation.UPDATE -> "Обновление"
            VersionRelation.SAME -> "Та же версия"
            VersionRelation.DOWNGRADE -> "Версия ниже установленной"
            VersionRelation.UNAVAILABLE -> "Не установлено или недоступно для проверки"
            VersionRelation.FAILURE -> "Не удалось проверить"
        }, style = MaterialTheme.typography.titleSmall)
        Text("В пакете: ${versionDescription(comparison.incoming)}")
        comparison.installed?.let { Text("Установлено: ${versionDescription(it)}") }
        Text("Это сравнение номера версии. Окончательную возможность установки, включая совместимость подписи, проверяет Android.",
            style = MaterialTheme.typography.bodySmall)
    }
}

internal fun versionDescription(version: PackageVersion): String =
    "${version.name.ifBlank { "Без названия версии" }} · код ${version.code}"

internal fun permissionSdkRange(permission: PermissionDeclaration): String = when {
    permission.maxSdk != null -> "SDK ${permission.minSdk}–${permission.maxSdk}"
    permission.minSdk > 1 -> "SDK от ${permission.minSdk}"
    else -> "Без ограничений SDK в декларации"
}
