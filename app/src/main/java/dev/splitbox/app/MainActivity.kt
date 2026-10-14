// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
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

    private fun chooseFile() {
        try {
            openDocument.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            viewModel.reportMessage("На устройстве нет системного выбора файлов. Откройте APK или архив в файловом менеджере через «Открыть с помощью SplitBox».", NoticeAction.CHOOSE_FILE)
        } catch (_: SecurityException) {
            viewModel.reportMessage("Android запретил открыть выбор файлов. Попробуйте передать APK или архив из файлового менеджера в SplitBox.", NoticeAction.CHOOSE_FILE)
        }
    }

    private fun chooseApks() {
        try {
            openApks.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            viewModel.reportMessage("На устройстве нет системного выбора файлов. Передайте APK одного приложения из файлового менеджера в SplitBox.", NoticeAction.CHOOSE_FILE)
        } catch (_: SecurityException) {
            viewModel.reportMessage("Android запретил открыть выбор файлов. Передайте APK одного приложения из файлового менеджера в SplitBox.", NoticeAction.CHOOSE_FILE)
        }
    }

    private fun chooseObbDestination(name: String) {
        try {
            createObbArchive.launch(name)
        } catch (_: ActivityNotFoundException) {
            viewModel.reportMessage("На устройстве нет системного диалога сохранения файлов. Экспорт OBB сейчас недоступен.")
        } catch (_: SecurityException) {
            viewModel.reportMessage("Android запретил открыть диалог сохранения. Экспорт OBB не начат.")
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
                viewModel.reportMessage("Откройте локальный APK или архив через файловый менеджер", NoticeAction.CHOOSE_FILE)
            }
        }
    }
}
