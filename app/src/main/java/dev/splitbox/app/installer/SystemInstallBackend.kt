// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.installer

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
        check(context.packageManager.canRequestPackageInstalls()) { "Разрешите установку из этого источника" }
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
                obbWarning = if (request.prepared.obbFiles.isNotEmpty()) "OBB не копируются системным способом. Экспортируйте ZIP и перенесите файлы в Android/obb/${request.prepared.metadata.packageName}." else null))
            journal.flush()
            installer.openSession(id).use { session ->
                request.apks.forEachIndexed { index, entry ->
                    currentCoroutineContext().ensureActive()
                    progress("Запись APK ${index + 1} из ${request.apks.size}", index.toFloat() / request.apks.size)
                    session.openWrite("split_$index.apk", 0, entry.file.length()).use { output ->
                        entry.file.inputStream().use { input ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
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
                journal.write(requireNotNull(journal.read()) { "Состояние системной сессии установки недоступно" }.copy(state = "COMMITTED"))
                journal.flush()
                session.commit(pendingIntent.intentSender)
                committed = true
            }
            BackendOutcome.SystemPending(id)
        } catch (e: Exception) {
            val stored = journal.read()
            if (stored?.sessionId == id && stored.state != "FINISHED") {
                journal.write(stored.copy(state = "FINISHED", success = false, message = e.message ?: "Ошибка установки", confirmation = null))
            }
            throw e
        } finally {
            if (!committed) runCatching { installer.abandonSession(id) }
        }
    }
}
