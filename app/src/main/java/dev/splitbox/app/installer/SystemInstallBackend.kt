// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.installer

import dev.splitbox.core.message
import dev.splitbox.core.nestedMessage

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import dev.splitbox.app.data.InstallJournal
import dev.splitbox.app.data.PendingInstall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class SystemInstallBackend(private val context: Context, private val journal: InstallJournal) : InstallBackend {
    override suspend fun install(request: InstallRequest, progress: (String, Float?) -> Unit): BackendOutcome = withContext(Dispatchers.IO) {
        check(context.packageManager.canRequestPackageInstalls()) { message("system_install_backend_allow_installation_from_this_source", "Разрешите установку из этого источника") }
        val installer = context.packageManager.packageInstaller
        val size = request.apks.fold(0L) { sum, entry -> Math.addExact(sum, entry.file.length()) }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(request.prepared.metadata.packageName)
            setSize(size)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        var committed = false
        try {
            journal.write(PendingInstall(id, request.prepared.metadata.packageName,
                request.prepared.metadata.versionName, request.appLabel, request.prepared.sourceName,
                request.prepared.workDir.absolutePath, System.currentTimeMillis(),
                request.settings.cleanAfterInstall, request.settings.saveHistory,
                obbWarning = if (request.prepared.obbFiles.isNotEmpty()) message("system_install_backend_obb_files_are_not_copied_with_the", "OBB не копируются системным способом. Экспортируйте ZIP и перенесите файлы в Android/obb/%1\$s.", request.prepared.metadata.packageName) else null))
            journal.flush()
            installer.openSession(id).use { session ->
                var written = 0L
                val reporting = TransferProgress(size) { bytes, fraction -> progress(message("system_install_backend_writing_apk_mib", "Запись APK · %1\$s МиБ", bytes / 1048576), fraction) }
                request.apks.forEachIndexed { index, entry ->
                    currentCoroutineContext().ensureActive()
                    progress(message("system_install_backend_writing_apk_of", "Запись APK %1\$s из %2\$s", index + 1, request.apks.size), (written.toDouble() / size).toFloat())
                    session.openWrite("split_$index.apk", 0, entry.file.length()).use { output ->
                        entry.file.inputStream().use { input ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                written = Math.addExact(written, count.toLong())
                                reporting.report(written)
                            }
                        }
                        session.fsync(output)
                    }
                }
                currentCoroutineContext().ensureActive()
                val intent = Intent(context, InstallResultReceiver::class.java).apply {
                    action = "${context.packageName}.INSTALL_RESULT.$id"
                    putExtra("splitbox_session", id)
                }
                val pendingIntent = PendingIntent.getBroadcast(context, id, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                request.onCommitStarted()
                // Сохраняем до commit: обратный вызов может поступить раньше возврата из commit.
                journal.write(requireNotNull(journal.read()) { message("system_install_backend_system_installation_session_state_unavailable", "Состояние системной сессии установки недоступно") }.copy(state = "COMMITTED"))
                journal.flush()
                session.commit(pendingIntent.intentSender)
                committed = true
            }
            BackendOutcome.SystemPending(id)
        } catch (e: Exception) {
            val stored = journal.read()
            if (stored?.sessionId == id && stored.state != "FINISHED") {
                journal.write(stored.copy(state = "FINISHED", success = false, message = e.message ?: message("split_box_view_model_installation_error", "Ошибка установки"), confirmation = null))
            }
            throw e
        } finally {
            if (!committed) runCatching { installer.abandonSession(id) }
        }
    }
}
