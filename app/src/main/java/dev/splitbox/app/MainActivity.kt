// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app

import dev.splitbox.core.message
import dev.splitbox.core.nestedMessage

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import dev.splitbox.app.ui.SplitBoxApp
import dev.splitbox.app.model.NoticeAction

class MainActivity : ComponentActivity() {
    private val viewModel: SplitBoxViewModel by viewModels()

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Некоторые поставщики документов дают только временный доступ; импортируем сразу.
            }
            viewModel.importUri(uri)
        }
    }

    private val openApks = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.importUris(uris)
    }

    private val createObbArchive = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let(viewModel::exportObb)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.refreshLanguage()
        enableEdgeToEdge()
        setContent {
            SplitBoxApp(
                viewModel = viewModel,
                window = window,
                onChooseFile = ::chooseFile,
                onChooseApks = ::chooseApks,
                onExportObb = ::chooseObbDestination,
                onRetrySave = viewModel::retrySelectionSave,
                onLaunchIntent = { startActivity(it) },
                onLaunchConfirmation = {
                    val options = android.app.ActivityOptions.makeBasic().apply {
                        // Android 14–15 поддерживают только прежний разрешающий режим отправителя.
                        @Suppress("DEPRECATION")
                        val mode = if (android.os.Build.VERSION.SDK_INT >= 36)
                                android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                            else android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        setPendingIntentBackgroundActivityStartMode(mode)
                    }
                    startIntentSender(it, null, 0, 0, 0, options.toBundle())
                },
                onOpenShizuku = ::openShizuku,
                onShizukuWebsite = ::openShizukuWebsite,
            )
        }
        if (savedInstanceState == null) importFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importFromIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onHostResumed()
    }

    private fun openShizuku() {
        try {
            val launch = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (launch != null) startActivity(launch) else reportShizukuLaunchFailure()
        } catch (_: ActivityNotFoundException) {
            reportShizukuLaunchFailure()
        } catch (_: SecurityException) {
            reportShizukuLaunchFailure()
        }
    }
    private fun reportShizukuLaunchFailure() {
        viewModel.reportMessage(message("shizuku_launch_unavailable", "Не удалось открыть Shizuku. Установите или откройте его отдельно; официальный сайт доступен в настройках."))
    }
    private fun openShizukuWebsite() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, "https://shizuku.rikka.app/download/".toUri()))
        } catch (_: ActivityNotFoundException) {
            reportShizukuWebsiteFailure()
        } catch (_: SecurityException) {
            reportShizukuWebsiteFailure()
        }
    }
    private fun reportShizukuWebsiteFailure() {
        viewModel.reportMessage(message("shizuku_website_unavailable", "Не удалось открыть сайт Shizuku. Откройте https://shizuku.rikka.app/download/ в браузере."))
    }

    private fun chooseFile() {
        try {
            openDocument.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            viewModel.reportMessage(message("main_activity_this_device_has_no_system_file_picker", "На устройстве нет системного выбора файлов. Откройте APK или архив в файловом менеджере через «Открыть с помощью SplitBox»."), NoticeAction.CHOOSE_FILE)
        } catch (_: SecurityException) {
            viewModel.reportMessage(message("main_activity_android_blocked_the_file_picker_try_sending", "Android запретил открыть выбор файлов. Попробуйте передать APK или архив из файлового менеджера в SplitBox."), NoticeAction.CHOOSE_FILE)
        }
    }

    private fun chooseApks() {
        try {
            openApks.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            viewModel.reportMessage(message("main_activity_this_device_has_no_system_file_picker_2", "На устройстве нет системного выбора файлов. Передайте APK одного приложения из файлового менеджера в SplitBox."), NoticeAction.CHOOSE_FILE)
        } catch (_: SecurityException) {
            viewModel.reportMessage(message("main_activity_android_blocked_the_file_picker_send_the", "Android запретил открыть выбор файлов. Передайте APK одного приложения из файлового менеджера в SplitBox."), NoticeAction.CHOOSE_FILE)
        }
    }

    private fun chooseObbDestination(name: String) {
        try {
            createObbArchive.launch(name)
        } catch (_: ActivityNotFoundException) {
            viewModel.reportMessage(message("main_activity_this_device_has_no_system_file_save", "На устройстве нет системного диалога сохранения файлов. Экспорт OBB сейчас недоступен."))
        } catch (_: SecurityException) {
            viewModel.reportMessage(message("main_activity_android_blocked_the_save_dialog_obb_export", "Android запретил открыть диалог сохранения. Экспорт OBB не начат."))
        }
    }

    private fun importFromIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            val streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            val uris = streams.ifEmpty {
                intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
            }
            viewModel.importUris(uris)
            return
        }
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            else -> null
        }
        if (uri != null) {
            if (uri.scheme == "content" || uri.scheme == "file") {
                viewModel.importUri(uri)
            } else {
                viewModel.reportMessage(message("main_activity_open_a_local_apk_or_archive_using", "Откройте локальный APK или архив через файловый менеджер"), NoticeAction.CHOOSE_FILE)
            }
        }
    }
}
