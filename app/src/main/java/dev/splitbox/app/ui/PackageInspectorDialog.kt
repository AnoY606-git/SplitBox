// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.ui

import dev.splitbox.app.R
import androidx.compose.ui.res.stringResource
import dev.splitbox.core.message
import dev.splitbox.core.nestedMessage

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
import dev.splitbox.app.ui.LocalizedText as Text
import androidx.compose.material3.Text as RawText
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
        TextButton(onClick = { opened = true }) { Text(stringResource(R.string.package_inspector_dialog_package_details)) }
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
        title = { Text(stringResource(R.string.package_inspector_dialog_package_details)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item("identity") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        RawText(label ?: prepared.metadata.packageName, style = MaterialTheme.typography.titleMedium)
                        SelectionContainer { Text(prepared.metadata.packageName, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                item("comparison_heading") {
                    HorizontalDivider()
                    Text(stringResource(R.string.package_inspector_dialog_version_comparison), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                }
                val details = inspection
                if (details == null) {
                    item("loading") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.package_inspector_dialog_checking_the_installed_version_and_selected_apks))
                        }
                    }
                } else {
                    item("comparison") { VersionComparisonBlock(details.comparison) }
                    item("permissions_heading") {
                        HorizontalDivider()
                        Text(stringResource(R.string.package_inspector_dialog_requested_permissions), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.package_inspector_dialog_declarations_from_the_selected_apks_only_these),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (!details.permissions.complete) {
                        item("incomplete") {
                            Text(stringResource(R.string.package_inspector_dialog_details_are_incomplete_permission_information_is_mis),
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    item("search") {
                        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.package_inspector_dialog_find_a_permission_by_name)) })
                        Text(message("package_inspector_dialog_showing_1_of_2", "Показано %1\$s из %2\$s", visiblePermissions.size, details.permissions.permissions.size),
                            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    if (visiblePermissions.isEmpty()) {
                        item("empty") {
                            Text(when {
                                query.isNotBlank() -> stringResource(R.string.package_inspector_dialog_no_permissions_match_this_search)
                                details.permissions.complete -> stringResource(R.string.package_inspector_dialog_the_selected_apks_contain_no_permission_declarations)
                                else -> stringResource(R.string.package_inspector_dialog_no_permission_declarations_available)
                            })
                        }
                    }
                    items(visiblePermissions, key = { "permission:${it.name}" }) { group ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SelectionContainer { RawText(group.name, style = MaterialTheme.typography.bodyMedium) }
                            Text(group.declarations.joinToString("; ") { permissionSdkRange(it) },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.history_screen_close)) } },
    )
}

@Composable
private fun VersionComparisonBlock(comparison: VersionComparison) {

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(when (comparison.relation) {
            VersionRelation.UPDATE -> stringResource(R.string.package_inspector_dialog_update)
            VersionRelation.SAME -> stringResource(R.string.package_inspector_dialog_same_version)
            VersionRelation.DOWNGRADE -> stringResource(R.string.package_inspector_dialog_older_than_the_installed_version)
            VersionRelation.UNAVAILABLE -> stringResource(R.string.package_inspector_dialog_not_installed_or_unavailable_to_check)
            VersionRelation.FAILURE -> stringResource(R.string.package_inspector_dialog_could_not_check)
        }, style = MaterialTheme.typography.titleSmall)
        Text(message("package_inspector_dialog_in_package_1", "В пакете: %1\$s", nestedMessage(versionDescription(comparison.incoming))))
        comparison.installed?.let { Text(message("package_inspector_dialog_installed_1", "Установлено: %1\$s", nestedMessage(versionDescription(it)))) }
        Text(stringResource(R.string.package_inspector_dialog_this_compares_version_numbers_android_determines_whe),
            style = MaterialTheme.typography.bodySmall)
    }
}

internal fun versionDescription(version: PackageVersion): String {
    val name = if (version.name.isBlank()) nestedMessage(message("install_screen_no_version_name", "Без названия версии")) else version.name
    return message("package_inspector_dialog_1_code_2", "%1\$s · код %2\$s", name, version.code)
}

internal fun permissionSdkRange(permission: PermissionDeclaration): String = when {
    permission.maxSdk?.let { it <= 0 } == true ->
        message("package_inspector_dialog_declaration_sdk_from_1_maxsdkversion_2_android", "В декларации: SDK от %1\$s, maxSdkVersion=%2\$s. Применимость определяет Android", permission.minSdk, permission.maxSdk)
    permission.maxSdk != null -> "SDK ${permission.minSdk}–${permission.maxSdk}"
    permission.minSdk > 1 -> message("package_inspector_dialog_sdk_from_1", "SDK от %1\$s", permission.minSdk)
    else -> message("package_inspector_dialog_no_sdk_restrictions_in_the_declaration", "Без ограничений SDK в декларации")
}
