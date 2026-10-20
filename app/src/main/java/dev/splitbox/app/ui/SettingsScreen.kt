// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ScreenLockPortrait
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SettingsSuggest
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.splitbox.app.R
import dev.splitbox.app.data.PackageSizePolicy
import dev.splitbox.app.model.AccessState
import dev.splitbox.app.model.AppLanguage
import dev.splitbox.app.model.AppSettings
import dev.splitbox.app.model.BackendMode
import dev.splitbox.app.model.ThemeMode
import dev.splitbox.app.ui.components.formatBytes
import dev.splitbox.app.ui.LocalizedIcon as Icon
import dev.splitbox.app.ui.LocalizedText as Text

@Composable
fun SettingsScreen(
    settings: AppSettings,
    access: AccessState,
    cacheBytes: Long,
    busy: Boolean,
    onUpdate: (AppSettings) -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onRootAccess: () -> Unit,
    onShizukuAccess: () -> Unit,
    onOpenShizuku: () -> Unit,
    onShizukuWebsite: () -> Unit,
    onRefreshAccess: () -> Unit,
    onClearCache: () -> Unit,
    onLicense: () -> Unit,
    onNotices: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val editable = !busy
    var picker by rememberSaveable { mutableStateOf<SettingsPicker?>(null) }

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item("heading") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.split_box_app_settings),
                    Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineLarge,
                )
                SettingsDescription(stringResource(R.string.settings_screen_installation_method_appearance_and_behavior_in_one))
            }
        }
        if (busy) item("busy") {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.tertiaryContainer) {
                Text(
                    stringResource(R.string.settings_screen_installation_settings_are_protected_from_changes_while),
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
        item("installation") {
            SettingsGroup(stringResource(R.string.settings_group_installation)) {
                SettingsSubheading(stringResource(R.string.history_screen_installation_method))
                Column(
                    Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    BackendChoice(
                        stringResource(R.string.history_presentation_system),
                        stringResource(R.string.settings_screen_standard_android_dialog_no_root_required),
                        Icons.Rounded.Shield,
                        settings.backend == BackendMode.SYSTEM,
                        editable,
                    ) { onUpdate(settings.copy(backend = BackendMode.SYSTEM)) }
                    BackendChoice(
                        "Root",
                        stringResource(R.string.settings_screen_install_using_su_and_a_root_access),
                        Icons.Rounded.Terminal,
                        settings.backend == BackendMode.ROOT,
                        editable,
                        status = access.rootStatus,
                        ready = access.rootGranted,
                    ) { onUpdate(settings.copy(backend = BackendMode.ROOT)) }
                    BackendChoice(
                        "Shizuku",
                        stringResource(R.string.settings_screen_install_using_a_separately_started_shizuku_service),
                        Icons.Rounded.SettingsSuggest,
                        settings.backend == BackendMode.SHIZUKU,
                        editable,
                        status = access.shizukuStatus,
                        ready = access.shizukuRunning && access.shizukuGranted,
                    ) { onUpdate(settings.copy(backend = BackendMode.SHIZUKU)) }
                }
                Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsDescription(stringResource(R.string.settings_screen_the_choice_is_always_explicit_the_app))
                    when (settings.backend) {
                        BackendMode.SYSTEM -> SettingsDescription(stringResource(R.string.settings_screen_android_will_request_permission_to_install_unknown))
                        BackendMode.ROOT -> {
                            SettingsDescription(stringResource(R.string.settings_screen_root_must_already_be_configured_the_button))
                            Button(onClick = onRootAccess, enabled = editable, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(if (access.rootGranted) R.string.settings_screen_check_root_again else R.string.settings_screen_request_root_access))
                            }
                        }
                        BackendMode.SHIZUKU -> {
                            SettingsDescription(stringResource(R.string.settings_screen_install_and_start_shizuku_separately_for_example))
                            Button(onClick = onShizukuAccess, enabled = editable, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(if (access.shizukuGranted) R.string.settings_screen_check_shizuku_access else R.string.settings_screen_request_shizuku_access))
                            }
                            if (!access.shizukuGranted) {
                                OutlinedButton(onClick = onOpenShizuku, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.settings_open_shizuku))
                                }
                            }
                            TextButton(onClick = onShizukuWebsite, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.settings_screen_official_shizuku_website), Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
                            }
                        }
                    }
                    TextButton(onClick = onRefreshAccess, enabled = editable, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_screen_refresh_access_status), Modifier.weight(1f))
                    }
                    SettingsDescription(stringResource(R.string.settings_screen_root_and_shizuku_depend_on_android_and))
                }
                SettingsDivider()
                SettingToggle(
                    stringResource(R.string.settings_screen_select_splits_automatically),
                    stringResource(R.string.settings_screen_select_splits_for_your_device_cpu_screen),
                    Icons.Rounded.Layers,
                    settings.autoSelectSplits,
                    editable,
                ) { onUpdate(settings.copy(autoSelectSplits = it)) }
                SettingToggle(
                    stringResource(R.string.settings_screen_copy_obb_files),
                    stringResource(R.string.settings_screen_root_or_shizuku_only_copy_data_to),
                    Icons.Rounded.Folder,
                    settings.installObb,
                    editable && settings.backend != BackendMode.SYSTEM,
                ) { onUpdate(settings.copy(installObb = it)) }
                SettingToggle(
                    stringResource(R.string.settings_screen_allow_downgrade),
                    stringResource(R.string.settings_screen_root_or_shizuku_only_android_may_reject),
                    Icons.Rounded.KeyboardDoubleArrowDown,
                    settings.allowDowngrade,
                    editable && settings.backend != BackendMode.SYSTEM,
                ) { onUpdate(settings.copy(allowDowngrade = it)) }
                SettingToggle(
                    stringResource(R.string.settings_screen_keep_screen_on),
                    stringResource(R.string.settings_screen_keep_the_screen_on_during_an_active),
                    Icons.Rounded.ScreenLockPortrait,
                    settings.keepScreenOn,
                ) { onUpdate(settings.copy(keepScreenOn = it)) }
            }
        }
        item("application") {
            SettingsGroup(stringResource(R.string.settings_group_application)) {
                SettingsValueRow(
                    stringResource(R.string.settings_language),
                    languageLabel(settings.language),
                    Icons.Rounded.Language,
                ) { picker = SettingsPicker.LANGUAGE }
                SettingsDivider()
                SettingsValueRow(
                    stringResource(R.string.settings_theme),
                    themeLabel(settings.theme),
                    Icons.Rounded.Brightness6,
                ) { picker = SettingsPicker.THEME }
                SettingToggle(
                    stringResource(R.string.settings_screen_system_colors),
                    stringResource(R.string.settings_screen_use_the_android_wallpaper_palette_instead_of),
                    Icons.Rounded.Palette,
                    settings.dynamicColors,
                ) { onUpdate(settings.copy(dynamicColors = it)) }
                SettingToggle(
                    stringResource(R.string.settings_screen_haptic_feedback),
                    stringResource(R.string.settings_screen_a_light_vibration_for_primary_actions_and),
                    Icons.Rounded.Vibration,
                    settings.haptics,
                ) { onUpdate(settings.copy(haptics = it)) }
            }
        }
        item("files") {
            SettingsGroup(stringResource(R.string.settings_group_files)) {
                SettingToggle(
                    stringResource(R.string.settings_screen_verify_apk_signatures),
                    stringResource(R.string.settings_screen_additional_cryptographic_verification_before_installation_and),
                    Icons.Rounded.Security,
                    settings.verifySignatures,
                    editable,
                ) { onUpdate(settings.copy(verifySignatures = it)) }
                SettingToggle(
                    stringResource(R.string.settings_screen_calculate_sha_256),
                    stringResource(R.string.settings_screen_show_the_source_file_checksum_after_preparation),
                    Icons.Rounded.DataObject,
                    settings.computeSha256,
                    editable,
                ) { onUpdate(settings.copy(computeSha256 = it)) }
                SettingsDivider()
                SettingsValueRow(
                    stringResource(R.string.settings_size_limit),
                    packageSizeLabel(settings.maxExpandedGiB),
                    Icons.Rounded.Storage,
                    enabled = editable,
                    description = stringResource(R.string.settings_size_limit_description),
                ) { picker = SettingsPicker.SIZE_LIMIT }
                SettingsDivider()
                SettingToggle(
                    stringResource(R.string.settings_screen_clean_up_after_installation),
                    stringResource(R.string.settings_screen_delete_temporary_data_after_the_operation_finishes),
                    Icons.Rounded.AutoAwesome,
                    settings.cleanAfterInstall,
                    editable,
                ) { onUpdate(settings.copy(cleanAfterInstall = it)) }
                SettingToggle(
                    stringResource(R.string.settings_screen_save_history),
                    stringResource(R.string.settings_screen_up_to_100_recent_results_on_this),
                    Icons.Rounded.History,
                    settings.saveHistory,
                    editable,
                ) { onUpdate(settings.copy(saveHistory = it)) }
                SettingsDivider()
                SettingsValueRow(
                    stringResource(R.string.settings_screen_clear_temporary_files),
                    formatBytes(cacheBytes),
                    Icons.Rounded.CleaningServices,
                    enabled = editable && cacheBytes > 0,
                    description = stringResource(R.string.settings_screen_cleanup_removes_only_splitbox_working_copies_it),
                    onClick = onClearCache,
                )
            }
        }
        item("project") {
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Code, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.split_box_app_about_splitbox), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                        Text("SplitBox · Android 14+", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                SettingsDescription(stringResource(R.string.settings_screen_no_ads_analytics_or_accounts_your_apks))
                Text("GNU GPL v3 · GPL-3.0-only", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                SettingsDescription(stringResource(R.string.settings_screen_source_code_build_instructions_and_dependency_licenses))
                TextButton(onClick = onLicense, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_screen_read_gpl_v3)) }
                TextButton(onClick = onNotices, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.legal_dialog_component_licenses)) }
            }
        }
    }

    when (picker) {
        SettingsPicker.LANGUAGE -> SettingsChoiceDialog(
            title = stringResource(R.string.settings_language),
            description = stringResource(R.string.settings_language_description),
            options = AppLanguage.entries.map { it to languageLabel(it) },
            selected = settings.language,
            onDismiss = { picker = null },
        ) {
            picker = null
            onLanguageChange(it)
        }
        SettingsPicker.THEME -> SettingsChoiceDialog(
            title = stringResource(R.string.settings_theme),
            options = ThemeMode.entries.map { it to themeLabel(it) },
            selected = settings.theme,
            onDismiss = { picker = null },
        ) {
            picker = null
            onUpdate(settings.copy(theme = it))
        }
        SettingsPicker.SIZE_LIMIT -> SettingsChoiceDialog(
            title = stringResource(R.string.settings_size_limit),
            description = stringResource(R.string.settings_screen_auto_limits_copying_and_extraction_to_the),
            options = PackageSizePolicy.choicesGiB.map { it to packageSizeLabel(it) },
            selected = settings.maxExpandedGiB,
            enabled = editable,
            onDismiss = { picker = null },
        ) {
            picker = null
            onUpdate(settings.copy(maxExpandedGiB = it))
        }
        null -> Unit
    }
}

private enum class SettingsPicker { LANGUAGE, THEME, SIZE_LIMIT }

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, Modifier.padding(start = 4.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
        }
    }
}

@Composable
private fun SettingsSubheading(title: String) {
    Text(title, Modifier.padding(horizontal = 4.dp, vertical = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun SettingsDescription(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun BackendChoice(
    title: String,
    description: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    status: String? = null,
    ready: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.medium, color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val foreground = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            Icon(icon, null, Modifier.size(22.dp), tint = foreground.copy(alpha = if (enabled) 1f else .5f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = foreground.copy(alpha = if (enabled) 1f else .5f))
                Text(description, style = MaterialTheme.typography.bodySmall, color = foreground.copy(alpha = if (enabled) .8f else .5f))
                if (status != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (ready) Icons.Rounded.CheckCircle else Icons.Rounded.Info, null, Modifier.size(14.dp), tint = foreground)
                        Text(status, style = MaterialTheme.typography.labelMedium, color = foreground)
                    }
                }
            }
            RadioButton(selected, onClick = null, enabled = enabled)
        }
    }
}

@Composable
private fun SettingToggle(
    title: String,
    description: String,
    icon: ImageVector,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    SettingRow(
        title,
        description,
        icon,
        enabled,
        Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    ) {
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun SettingsValueRow(
    title: String,
    value: String,
    icon: ImageVector,
    enabled: Boolean = true,
    description: String? = null,
    onClick: () -> Unit,
) {
    SettingRow(
        title,
        description,
        icon,
        enabled,
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        value = value,
    ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .5f))
    }
}

@Composable
private fun SettingRow(
    title: String,
    description: String?,
    icon: ImageVector,
    enabled: Boolean,
    modifier: Modifier,
    value: String? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .5f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .5f))
            value?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else .5f)) }
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .65f)) }
        }
        trailing()
    }
}

@Composable
private fun <T> SettingsChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onDismiss: () -> Unit,
    description: String? = null,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // Варианты видны сразу; длинное пояснение доступно после них в общей прокрутке.
            LazyColumn(Modifier.heightIn(max = 440.dp).selectableGroup()) {
                items(options.size) { index ->
                    val (value, label) = options[index]
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp)
                            .selectable(selected == value, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(value) })
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected == value, onClick = null, enabled = enabled)
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (description != null) item("description") {
                    Text(description, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.history_screen_close)) }
        },
    )
}

@Composable
private fun languageLabel(language: AppLanguage): String = stringResource(when (language) {
    AppLanguage.SYSTEM -> R.string.settings_language_system
    AppLanguage.RUSSIAN -> R.string.settings_language_russian
    AppLanguage.ENGLISH -> R.string.settings_language_english
})

@Composable
private fun themeLabel(theme: ThemeMode): String = stringResource(when (theme) {
    ThemeMode.SYSTEM -> R.string.settings_screen_system
    ThemeMode.DARK -> R.string.settings_screen_dark
    ThemeMode.LIGHT -> R.string.settings_screen_light
})

@Composable
private fun packageSizeLabel(gib: Int): String = if (gib == 0) {
    stringResource(R.string.settings_screen_auto)
} else {
    stringResource(R.string.settings_screen_1_gib, gib)
}
