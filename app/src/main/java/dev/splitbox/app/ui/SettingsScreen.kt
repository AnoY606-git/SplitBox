// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.ui

import dev.splitbox.core.message
import dev.splitbox.core.nestedMessage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SettingsSuggest
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import dev.splitbox.app.ui.LocalizedIcon as Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import dev.splitbox.app.ui.LocalizedText as Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import dev.splitbox.app.model.AccessState
import dev.splitbox.app.model.AppSettings
import dev.splitbox.app.model.BackendMode
import dev.splitbox.app.model.ThemeMode
import dev.splitbox.app.model.AppLanguage
import dev.splitbox.app.R
import androidx.compose.ui.res.stringResource
import dev.splitbox.app.ui.components.Eyebrow
import dev.splitbox.app.ui.components.Panel
import dev.splitbox.app.ui.components.SectionHeading
import dev.splitbox.app.ui.components.formatBytes

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
    LazyColumn(modifier, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item("heading") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow(stringResource(R.string.settings_screen_your_choice_your_control))
                Text(stringResource(R.string.settings_screen_made_for_you), style = MaterialTheme.typography.headlineLarge)
                Text(stringResource(R.string.settings_screen_installation_method_appearance_and_behavior_in_one), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (busy) item("busy") {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.tertiaryContainer) {
                Text(stringResource(R.string.settings_screen_installation_settings_are_protected_from_changes_while), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
        item("backend") {
            Panel(Modifier.fillMaxWidth()) {
                SectionHeading(stringResource(R.string.history_screen_installation_method), stringResource(R.string.settings_screen_the_choice_is_always_explicit_the_app))
                BackendChoice(stringResource(R.string.history_presentation_system), stringResource(R.string.settings_screen_standard_android_dialog_no_root_required), Icons.Rounded.Shield, settings.backend == BackendMode.SYSTEM, editable) { onUpdate(settings.copy(backend = BackendMode.SYSTEM)) }
                BackendChoice("Root", stringResource(R.string.settings_screen_install_using_su_and_a_root_access), Icons.Rounded.Terminal, settings.backend == BackendMode.ROOT, editable) { onUpdate(settings.copy(backend = BackendMode.ROOT)) }
                BackendChoice("Shizuku", stringResource(R.string.settings_screen_install_using_a_separately_started_shizuku_service), Icons.Rounded.SettingsSuggest, settings.backend == BackendMode.SHIZUKU, editable) { onUpdate(settings.copy(backend = BackendMode.SHIZUKU)) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                when (settings.backend) {
                    BackendMode.SYSTEM -> {
                        Text(stringResource(R.string.settings_screen_android_will_request_permission_to_install_unknown), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BackendMode.ROOT -> {
                        AccessExplanation(stringResource(R.string.settings_screen_root_access), access.rootStatus, access.rootGranted)
                        Text(stringResource(R.string.settings_screen_root_must_already_be_configured_the_button), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onRootAccess, enabled = editable, modifier = Modifier.fillMaxWidth()) { Text(if (access.rootGranted) stringResource(R.string.settings_screen_check_root_again) else stringResource(R.string.settings_screen_request_root_access)) }
                    }
                    BackendMode.SHIZUKU -> {
                        AccessExplanation(stringResource(R.string.settings_screen_shizuku_service), access.shizukuStatus, access.shizukuRunning && access.shizukuGranted)
                        Text(stringResource(R.string.settings_screen_install_and_start_shizuku_separately_for_example), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onShizukuAccess, enabled = editable, modifier = Modifier.fillMaxWidth()) { Text(if (access.shizukuGranted) stringResource(R.string.settings_screen_check_shizuku_access) else stringResource(R.string.settings_screen_request_shizuku_access)) }
                        if (!access.shizukuGranted) {
                            OutlinedButton(onClick = onOpenShizuku, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.settings_open_shizuku))
                            }
                        }
                        TextButton(onClick = onShizukuWebsite, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.settings_screen_official_shizuku_website))
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
                        }
                    }
                }
                TextButton(onClick = onRefreshAccess, enabled = editable, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_screen_refresh_access_status))
                }
                Text(stringResource(R.string.settings_screen_root_and_shizuku_depend_on_android_and), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item("language") {
            Panel(Modifier.fillMaxWidth()) {
                SectionHeading(stringResource(R.string.settings_language), stringResource(R.string.settings_language_description))
                AppLanguage.entries.forEach { language ->
                    val label = stringResource(when (language) {
                        AppLanguage.SYSTEM -> R.string.settings_language_system
                        AppLanguage.RUSSIAN -> R.string.settings_language_russian
                        AppLanguage.ENGLISH -> R.string.settings_language_english
                    })
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(settings.language == language, role = Role.RadioButton,
                            onClick = { onLanguageChange(language) }),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(settings.language == language, onClick = null)
                        Text(label, Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        item("theme") {
            Panel(Modifier.fillMaxWidth()) {
                SectionHeading(stringResource(R.string.settings_screen_appearance))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemeMode.entries.forEach { theme ->
                        val selected = settings.theme == theme
                        OutlinedButton(
                            onClick = { onUpdate(settings.copy(theme = theme)) }, modifier = Modifier.weight(1f).semantics { this.selected = selected },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 14.dp),
                            colors = if (selected) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) else ButtonDefaults.outlinedButtonColors(),
                        ) {
                            Text(when (theme) { ThemeMode.SYSTEM -> stringResource(R.string.settings_screen_system); ThemeMode.DARK -> stringResource(R.string.settings_screen_dark); ThemeMode.LIGHT -> stringResource(R.string.settings_screen_light) }, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                SettingToggle(stringResource(R.string.settings_screen_system_colors), stringResource(R.string.settings_screen_use_the_android_wallpaper_palette_instead_of), settings.dynamicColors) { onUpdate(settings.copy(dynamicColors = it)) }
                SettingToggle(stringResource(R.string.settings_screen_haptic_feedback), stringResource(R.string.settings_screen_a_light_vibration_for_primary_actions_and), settings.haptics) { onUpdate(settings.copy(haptics = it)) }
                SettingToggle(stringResource(R.string.settings_screen_keep_screen_on), stringResource(R.string.settings_screen_keep_the_screen_on_during_an_active), settings.keepScreenOn) { onUpdate(settings.copy(keepScreenOn = it)) }
            }
        }
        item("validation") {
            Panel(Modifier.fillMaxWidth()) {
                SectionHeading(stringResource(R.string.settings_screen_preparation_and_verification))
                SettingToggle(stringResource(R.string.settings_screen_select_splits_automatically), stringResource(R.string.settings_screen_select_splits_for_your_device_cpu_screen), settings.autoSelectSplits, editable) { onUpdate(settings.copy(autoSelectSplits = it)) }
                SettingToggle(stringResource(R.string.settings_screen_verify_apk_signatures), stringResource(R.string.settings_screen_additional_cryptographic_verification_before_installation_and), settings.verifySignatures, editable) { onUpdate(settings.copy(verifySignatures = it)) }
                SettingToggle(stringResource(R.string.settings_screen_calculate_sha_256), stringResource(R.string.settings_screen_show_the_source_file_checksum_after_preparation), settings.computeSha256, editable) { onUpdate(settings.copy(computeSha256 = it)) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SectionHeading(stringResource(R.string.settings_screen_package_size_and_free_space), stringResource(R.string.settings_screen_auto_limits_copying_and_extraction_to_the))
                dev.splitbox.app.data.PackageSizePolicy.choicesGiB.chunked(4).forEach { limits ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        limits.forEach { gib ->
                            OutlinedButton(
                                onClick = { onUpdate(settings.copy(maxExpandedGiB = gib)) }, modifier = Modifier.weight(1f).semantics { selected = settings.maxExpandedGiB == gib }, enabled = editable,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                                colors = if (settings.maxExpandedGiB == gib) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) else ButtonDefaults.outlinedButtonColors(),
                            ) { Text(if (gib == 0) stringResource(R.string.settings_screen_auto) else message("settings_screen_1_gib", "%1\$s ГиБ", gib), maxLines = 1) }
                        }
                    }
                }
            }
        }
        item("installation") {
            Panel(Modifier.fillMaxWidth()) {
                SectionHeading(stringResource(R.string.settings_screen_installation_options))
                SettingToggle(stringResource(R.string.settings_screen_copy_obb_files), stringResource(R.string.settings_screen_root_or_shizuku_only_copy_data_to), settings.installObb, editable && settings.backend != BackendMode.SYSTEM) { onUpdate(settings.copy(installObb = it)) }
                SettingToggle(stringResource(R.string.settings_screen_allow_downgrade), stringResource(R.string.settings_screen_root_or_shizuku_only_android_may_reject), settings.allowDowngrade, editable && settings.backend != BackendMode.SYSTEM) { onUpdate(settings.copy(allowDowngrade = it)) }
            }
        }
        item("storage") {
            Panel(Modifier.fillMaxWidth()) {
                SectionHeading(stringResource(R.string.settings_screen_data_on_this_device))
                SettingToggle(stringResource(R.string.settings_screen_clean_up_after_installation), stringResource(R.string.settings_screen_delete_temporary_data_after_the_operation_finishes), settings.cleanAfterInstall, editable) { onUpdate(settings.copy(cleanAfterInstall = it)) }
                SettingToggle(stringResource(R.string.settings_screen_save_history), stringResource(R.string.settings_screen_up_to_100_recent_results_on_this), settings.saveHistory, editable) { onUpdate(settings.copy(saveHistory = it)) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CleaningServices, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_screen_temporary_files), style = MaterialTheme.typography.titleMedium)
                        Text(formatBytes(cacheBytes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                FilledTonalButton(onClick = onClearCache, enabled = editable && cacheBytes > 0, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_screen_clear_temporary_files)) }
                Text(stringResource(R.string.settings_screen_cleanup_removes_only_splitbox_working_copies_it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item("opensource") {
            Panel(Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Code, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                SectionHeading(stringResource(R.string.settings_screen_open_by_design), "SplitBox · Android 14+")
                Text(stringResource(R.string.settings_screen_no_ads_analytics_or_accounts_your_apks), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("GNU GPL v3 · GPL-3.0-only", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.settings_screen_source_code_build_instructions_and_dependency_licenses), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onLicense, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_screen_read_gpl_v3)) }
                TextButton(onClick = onNotices, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.legal_dialog_component_licenses)) }
            }
        }
    }
}

@Composable
private fun BackendChoice(title: String, description: String, icon: ImageVector, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(23.dp), tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected, onClick = null, enabled = enabled)
        }
    }
}

@Composable
private fun SettingToggle(title: String, description: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .65f))
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun AccessExplanation(title: String, status: String, granted: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.Info, null, Modifier.size(22.dp), tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
