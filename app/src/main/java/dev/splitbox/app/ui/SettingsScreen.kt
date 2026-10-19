// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.ui

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import dev.splitbox.app.model.AccessState
import dev.splitbox.app.model.AppSettings
import dev.splitbox.app.model.BackendMode
import dev.splitbox.app.model.ThemeMode
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
    onRootAccess: () -> Unit,
    onShizukuAccess: () -> Unit,
    onRefreshAccess: () -> Unit,
    onClearCache: () -> Unit,
    onLicense: () -> Unit,
    onNotices: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val editable = !busy
    LazyColumn(modifier, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item("heading") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow("ВАШ ВЫБОР, ВАШ КОНТРОЛЬ")
                Text("Настроено\nпод вас", style = MaterialTheme.typography.headlineLarge)
                Text("Способ установки, внешний вид и поведение — в одном месте.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (busy) item("busy") {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.tertiaryContainer) {
                Text("Пока идёт операция, параметры установки защищены от изменений.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
        item("backend") {
            Panel(Modifier.fillMaxWidth()) {
                SectionHeading("Способ установки", "Выбор всегда явный. Приложение не переключается на повышенные права автоматически.")
                BackendChoice("Системный", "Стандартный диалог Android. Root не требуется.", Icons.Rounded.Shield, settings.backend == BackendMode.SYSTEM, editable) { onUpdate(settings.copy(backend = BackendMode.SYSTEM)) }
                BackendChoice("Root", "Установка через su и менеджер Root-доступа.", Icons.Rounded.Terminal, settings.backend == BackendMode.ROOT, editable) { onUpdate(settings.copy(backend = BackendMode.ROOT)) }
                BackendChoice("Shizuku", "Установка через отдельно запущенный сервис Shizuku.", Icons.Rounded.SettingsSuggest, settings.backend == BackendMode.SHIZUKU, editable) { onUpdate(settings.copy(backend = BackendMode.SHIZUKU)) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                when (settings.backend) {
                    BackendMode.SYSTEM -> {
                        Text("Android запросит разрешение устанавливать неизвестные приложения, если оно ещё не выдано. Каждая установка подтверждается системным диалогом.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BackendMode.ROOT -> {
                        AccessExplanation("Root-доступ", access.rootStatus, access.rootGranted)
                        Text("Нужен заранее настроенный Root. Кнопка ниже запускает su и может вызвать запрос вашего Root-менеджера. Повышенные права используются для установки и отдельно разрешённого переноса OBB.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onRootAccess, enabled = editable, modifier = Modifier.fillMaxWidth()) { Text(if (access.rootGranted) "Проверить Root повторно" else "Запросить Root-доступ") }
                    }
                    BackendMode.SHIZUKU -> {
                        AccessExplanation("Сервис Shizuku", access.shizukuStatus, access.shizukuRunning && access.shizukuGranted)
                        Text("Установите и запустите Shizuku отдельно, например через беспроводную отладку, затем предоставьте SplitBox доступ. После перезагрузки устройства сервис может потребовать повторного запуска.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onShizukuAccess, enabled = editable, modifier = Modifier.fillMaxWidth()) { Text(if (access.shizukuGranted) "Проверить доступ Shizuku" else "Запросить доступ Shizuku") }
                        TextButton(onClick = { uriHandler.openUri("https://shizuku.rikka.app/download/") }, modifier = Modifier.fillMaxWidth()) {
                            Text("Официальный сайт Shizuku")
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
                        }
                    }
                }
                TextButton(onClick = onRefreshAccess, enabled = editable, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Обновить статус доступа")
                }
                Text("Root и Shizuku зависят от Android и прошивки. Проверки Package Manager и ограничения производителя продолжают действовать.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item("theme") {
            Panel(Modifier.fillMaxWidth()) {
                SectionHeading("Внешний вид")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemeMode.entries.forEach { theme ->
                        val selected = settings.theme == theme
                        OutlinedButton(
                            onClick = { onUpdate(settings.copy(theme = theme)) }, modifier = Modifier.weight(1f).semantics { this.selected = selected },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 14.dp),
                            colors = if (selected) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) else ButtonDefaults.outlinedButtonColors(),
                        ) {
                            Text(when (theme) { ThemeMode.SYSTEM -> "Система"; ThemeMode.DARK -> "Тёмная"; ThemeMode.LIGHT -> "Светлая" }, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                SettingToggle("Системные цвета", "Использовать палитру обоев Android вместо фирменной мятной темы.", settings.dynamicColors) { onUpdate(settings.copy(dynamicColors = it)) }
                SettingToggle("Тактильный отклик", "Лёгкая вибрация при основных действиях и смене раздела.", settings.haptics) { onUpdate(settings.copy(haptics = it)) }
                SettingToggle("Не гасить экран", "Поддерживать экран включённым во время активной операции, пока SplitBox открыт.", settings.keepScreenOn) { onUpdate(settings.copy(keepScreenOn = it)) }
            }
        }
        item("validation") {
            Panel(Modifier.fillMaxWidth()) {
                SectionHeading("Подготовка и проверка")
                SettingToggle("Подбирать части автоматически", "Выбирать части под процессор, экран и языки устройства. При отключении выбираются все совместимые необязательные части.", settings.autoSelectSplits, editable) { onUpdate(settings.copy(autoSelectSplits = it)) }
                SettingToggle("Проверять подписи APK", "Дополнительная криптографическая проверка до установки. Системная проверка Android действует всегда.", settings.verifySignatures, editable) { onUpdate(settings.copy(verifySignatures = it)) }
                SettingToggle("Вычислять SHA-256", "Показать контрольную сумму исходного файла после подготовки.", settings.computeSha256, editable) { onUpdate(settings.copy(computeSha256 = it)) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SectionHeading("Размер пакета и свободное место", "Авто ограничивает копирование и распаковку свободным местом на диске, оставляя резерв 64 МиБ. Для архива нужны место под исходник, распакованные данные и дополнительную копию APK в сессии Android. Ручной лимит ограничивает исходник и распакованные данные по отдельности. Android и файловая система могут устанавливать свои ограничения.")
                dev.splitbox.app.data.PackageSizePolicy.choicesGiB.chunked(4).forEach { limits ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        limits.forEach { gib ->
                            OutlinedButton(
                                onClick = { onUpdate(settings.copy(maxExpandedGiB = gib)) }, modifier = Modifier.weight(1f).semantics { selected = settings.maxExpandedGiB == gib }, enabled = editable,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                                colors = if (settings.maxExpandedGiB == gib) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) else ButtonDefaults.outlinedButtonColors(),
                            ) { Text(if (gib == 0) "Авто" else "$gib ГиБ", maxLines = 1) }
                        }
                    }
                }
            }
        }
        item("installation") {
            Panel(Modifier.fillMaxWidth()) {
                SectionHeading("Параметры установки")
                SettingToggle("Переносить OBB", "Только Root или Shizuku: переносить данные в Android/obb установленного приложения. При системном способе доступен экспорт ZIP.", settings.installObb, editable && settings.backend != BackendMode.SYSTEM) { onUpdate(settings.copy(installObb = it)) }
                SettingToggle("Разрешить понижение версии", "Только Root или Shizuku. Android может отклонить downgrade даже при включённой опции.", settings.allowDowngrade, editable && settings.backend != BackendMode.SYSTEM) { onUpdate(settings.copy(allowDowngrade = it)) }
            }
        }
        item("storage") {
            Panel(Modifier.fillMaxWidth()) {
                SectionHeading("Данные на устройстве")
                SettingToggle("Очищать после установки", "Удалять временные данные после завершения операции. Исходные пользовательские файлы сохраняются.", settings.cleanAfterInstall, editable) { onUpdate(settings.copy(cleanAfterInstall = it)) }
                SettingToggle("Сохранять историю", "До 100 последних результатов на этом устройстве. Отключение не удаляет уже сохранённые записи.", settings.saveHistory, editable) { onUpdate(settings.copy(saveHistory = it)) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CleaningServices, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text("Временные файлы", style = MaterialTheme.typography.titleMedium)
                        Text(formatBytes(cacheBytes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                FilledTonalButton(onClick = onClearCache, enabled = editable && cacheBytes > 0, modifier = Modifier.fillMaxWidth()) { Text("Очистить временные файлы") }
                Text("Очистка удаляет только рабочие копии SplitBox. Во время операции она недоступна.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item("opensource") {
            Panel(Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Code, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                SectionHeading("Открыто по замыслу", "SplitBox · Android 14+")
                Text("Без рекламы, аналитики и аккаунтов. Ваши APK обрабатываются локально; сетевое разрешение не используется.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("GNU GPL v3 · GPL-3.0-only", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("Исходный код, инструкции сборки и лицензии зависимостей входят в комплект проекта. Распространение модифицированных версий регулируется GPL v3.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onLicense, modifier = Modifier.fillMaxWidth()) { Text("Читать GPL v3") }
                TextButton(onClick = onNotices, modifier = Modifier.fillMaxWidth()) { Text("Лицензии компонентов") }
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
