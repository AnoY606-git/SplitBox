// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.splitbox.app.BuildConfig
import dev.splitbox.app.SplitBoxViewModel
import dev.splitbox.app.model.NoticeAction
import dev.splitbox.app.model.OperationPhase
import dev.splitbox.app.model.UiEvent
import dev.splitbox.app.ui.components.SplitBoxMark
import dev.splitbox.app.ui.theme.SplitBoxTheme
import kotlinx.coroutines.flow.collect

private enum class Destination(val label: String, val icon: ImageVector) {
    INSTALL("Установка", Icons.Rounded.Widgets),
    HISTORY("История", Icons.Rounded.History),
    SETTINGS("Настройки", Icons.Rounded.Settings),
}

internal fun OperationPhase.isBusy(): Boolean = this == OperationPhase.COPYING || this == OperationPhase.ANALYZING ||
    this == OperationPhase.INSTALLING || this == OperationPhase.WAITING_USER

@Composable
fun SplitBoxApp(
    viewModel: SplitBoxViewModel,
    window: Window,
    onChooseFile: () -> Unit,
    onChooseApks: () -> Unit,
    onExportObb: (String) -> Unit,
    onLaunchIntent: (Intent) -> Unit,
    onRetrySave: () -> Unit = {},
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val access by viewModel.access.collectAsStateWithLifecycle()
    val cacheBytes by viewModel.cacheBytes.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val screenStates = rememberSaveableStateHolder()
    var destinationName by rememberSaveable { mutableStateOf(Destination.INSTALL.name) }
    val destination = Destination.valueOf(destinationName)
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var legalDocumentName by rememberSaveable { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val haptics = LocalHapticFeedback.current
    fun act(action: () -> Unit) {
        if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        action()
    }

    // Подтверждение запуска отправляется лишь после принятия запуска системой Android.
    // Сохранённый токен ViewModel ожидает доставки, пока нет подписчика в состоянии STARTED.
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { event ->
                when (event) {
                    is UiEvent.LaunchIntent -> try {
                        val token = event.acknowledgementToken
                        if (token != null && !viewModel.isIntentLaunchCurrent(token)) return@collect
                        onLaunchIntent(event.intent)
                        event.acknowledgementToken?.let { viewModel.acknowledgeIntentLaunch(it, true) }
                    } catch (_: ActivityNotFoundException) {
                        event.acknowledgementToken?.let { viewModel.acknowledgeIntentLaunch(it, false) }
                        viewModel.reportMessage("Android не нашёл приложение для этого действия")
                    } catch (_: SecurityException) {
                        event.acknowledgementToken?.let { viewModel.acknowledgeIntentLaunch(it, false) }
                        viewModel.reportMessage("Android запретил открыть это действие")
                    }
                    is UiEvent.Message -> viewModel.reportMessage(event.text)
                }
            }
        }
    }
    LaunchedEffect(ui.phase) {
        if (ui.phase == OperationPhase.COPYING || ui.phase == OperationPhase.ANALYZING) {
            destinationName = Destination.INSTALL.name
        }
    }
    DisposableEffect(window, ui.phase, settings.keepScreenOn, lifecycle) {
        fun updateScreenFlag() {
            if (settings.keepScreenOn && ui.phase.isBusy() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ -> updateScreenFlag() }
        lifecycle.addObserver(observer)
        updateScreenFlag()
        onDispose {
            lifecycle.removeObserver(observer)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    SplitBoxTheme(settings) { dark ->
        SideEffect {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(start = 22.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SplitBoxMark(Modifier.size(30.dp))
                    Text("SplitBox", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { showAbout = true }) { Icon(Icons.Rounded.Info, "О SplitBox") }
                }
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Destination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = item == destination,
                            onClick = { act { destinationName = item.name } },
                            icon = { Icon(item.icon, null) },
                            label = { Text(item.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                val screenModifier = Modifier.widthIn(max = 840.dp).fillMaxSize()
                screenStates.SaveableStateProvider(destination.name) {
                    when (destination) {
                        Destination.INSTALL -> InstallScreen(
                            ui = ui, settings = settings, access = access,
                            onChooseFile = { act(onChooseFile) },
                            onChooseApks = { act(onChooseApks) },
                            onInstall = { act(viewModel::install) },
                            onCancel = viewModel::cancelOperation,
                            onReset = viewModel::reset,
                            onToggleSplit = { path -> act { viewModel.toggleSplit(path) } },
                            onOpenApp = viewModel::openInstalledApp,
                            onExportObb = onExportObb,
                            onSettings = { destinationName = Destination.SETTINGS.name },
                            onCheckStatus = viewModel::checkStatus,
                            modifier = screenModifier,
                            onRetrySave = onRetrySave,
                        )
                        Destination.HISTORY -> HistoryScreen(history, viewModel::clearHistory, screenModifier)
                        Destination.SETTINGS -> SettingsScreen(
                            settings, access, cacheBytes, ui.phase.isBusy(),
                            onUpdate = viewModel::updateSettings,
                            onRootAccess = viewModel::requestRootAccess,
                            onShizukuAccess = viewModel::requestShizukuAccess,
                            onRefreshAccess = viewModel::refreshAccess,
                            onClearCache = viewModel::clearCache,
                            onLicense = { legalDocumentName = LegalDocument.LICENSE.name },
                            onNotices = { legalDocumentName = LegalDocument.NOTICES.name },
                            modifier = screenModifier,
                        )
                    }
                }
            }
        }
        notice?.let { message ->
            AlertDialog(
                onDismissRequest = viewModel::dismissNotice,
                title = { Text("Сообщение SplitBox") },
                text = {
                    SelectionContainer {
                        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                            Text(message.text, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.dismissNotice()
                        when (message.action) {
                            NoticeAction.CHOOSE_FILE -> onChooseFile()
                            NoticeAction.RETRY_SAVE -> onRetrySave()
                            null -> Unit
                        }
                    }) { Text(when (message.action) {
                        NoticeAction.CHOOSE_FILE -> "Выбрать файл"
                        NoticeAction.RETRY_SAVE -> "Повторить сохранение"
                        null -> "Понятно"
                    }) }
                },
                dismissButton = { TextButton(onClick = viewModel::dismissNotice) { Text("Закрыть") } },
            )
        }
        if (showAbout) {
            AlertDialog(
                onDismissRequest = { showAbout = false },
                icon = { SplitBoxMark(Modifier.size(48.dp)) },
                title = { Text("Открытая коробка возможностей") },
                text = { Text("SplitBox ${BuildConfig.VERSION_NAME} — открытый установщик APK и архивов с частями приложения для Android 14+.\n\nАвтор: AnoY606-git.\nhttps://github.com/AnoY606-git\n\n© 2026 AnoY606-git и участники SplitBox.\n\nБез рекламы, аккаунтов и аналитики. Все данные остаются на устройстве.\n\nGNU GPL v3 (GPL-3.0-only). При распространении кода сохраняйте уведомления об авторстве и лицензии. Исходники входят в комплект проекта. Программа предоставляется без гарантий.") },
                confirmButton = { TextButton(onClick = { showAbout = false }) { Text("Понятно") } },
                dismissButton = { TextButton(onClick = { showAbout = false; legalDocumentName = LegalDocument.ATTRIBUTION.name }) { Text("Авторство и лицензия") } },
            )
        }
        legalDocumentName?.let { name -> LegalDialog(LegalDocument.valueOf(name)) { legalDocumentName = null } }
    }
}
