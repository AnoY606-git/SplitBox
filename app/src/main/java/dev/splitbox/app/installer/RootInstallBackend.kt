// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.installer

import dev.splitbox.core.message
import dev.splitbox.core.nestedMessage

import dev.splitbox.app.model.OperationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

class RootInstallBackend(private val runner: CommandRunner = ProcessRunner()) : InstallBackend {
    suspend fun requestAccess(): Boolean = runInterruptible(Dispatchers.IO) {
        val result = runner.run(listOf("su", "-c", "id"), timeoutSeconds = 20)
        result.exitCode == 0 && Regex("(?:^|\\s)uid=0(?:\\(|\\s)").containsMatchIn(result.output)
    }
    override suspend fun install(request: InstallRequest, progress: (String, Float?) -> Unit): BackendOutcome {
        var sessionId: Int? = null
        var committed = false
        var commitMayHaveRun = false
        var commitResolved = false
        try {
            val size = request.apks.fold(0L) { sum, apk -> Math.addExact(sum, apk.file.length()) }
            progress(message("root_install_backend_root_creating_session", "Root: создание сессии"), null)
            val id = runInterruptible(Dispatchers.IO) {
                PmProtocol.sessionId(runner.run(listOf("su", "-c", PmProtocol.create(request.userId, size, request.settings.allowDowngrade)))).also {
                    // ID принадлежит нам ещё до отменяемого возврата из IO.
                    sessionId = it
                    request.onSessionCreated(it)
                }
            }
            var written = 0L
            val reporting = TransferProgress(size) { bytes, fraction -> progress(message("root_install_backend_root_writing_apk_mib", "Root: запись APK · %1\$s МиБ", bytes / 1048576), fraction) }
            request.apks.forEachIndexed { index, apk ->
                currentCoroutineContext().ensureActive()
                progress(message("root_install_backend_root_writing_apk_of", "Root: запись APK %1\$s из %2\$s", index + 1, request.apks.size), (written.toDouble() / size).toFloat())
                runInterruptible(Dispatchers.IO) {
                    apk.file.inputStream().use { input ->
                        runner.run(listOf("su", "-c", PmProtocol.write(id, index, apk.file.length())),
                            ProgressInputStream(input) { reporting.report(written + it) },
                            timeoutSeconds = TransferPolicy.timeoutSeconds(apk.file.length())).requireSuccess()
                    }
                }
                written = Math.addExact(written, apk.file.length())
                reporting.report(written)
            }
            currentCoroutineContext().ensureActive()
            progress(message("root_install_backend_root_installing", "Root: установка"), null)
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                request.onCommitStarted()
                withContext(NonCancellable) {
                    commitMayHaveRun = true
                    val result = runner.run(listOf("su", "-c", PmProtocol.commit(id)), timeoutSeconds = TransferPolicy.timeoutSeconds(size))
                    commitResolved = result.success || result.provenCommitFailure
                    result.requireCommitSuccess(request.prepared.metadata.packageName)
                    committed = true
                    request.onCommitted()
                }
            }
            val warning = copyObb(request, progress)
            return BackendOutcome.Finished(OperationResult(true, message("root_install_backend_app_installed_via_root", "Приложение установлено через Root"), request.prepared.metadata.packageName, warning))
        } catch (e: Exception) {
            if (commitMayHaveRun && !commitResolved && e !is UncertainInstallException) {
                throw UncertainInstallException(request.prepared.metadata.packageName,
                    message("root_install_backend_no_response_from_package_manager_was_received", "Ответ Package Manager после начала установки не получен. Результат неизвестен; проверьте приложение.")).apply { initCause(e) }
            }
            throw e
        } finally {
            val abandonedId = sessionId
            if (!committed && (!commitMayHaveRun || commitResolved) && abandonedId != null) withContext(NonCancellable + Dispatchers.IO) {
                runCatching { runner.run(listOf("su", "-c", PmProtocol.abandon(abandonedId)), timeoutSeconds = 15) }
            }
        }
    }
    private suspend fun copyObb(request: InstallRequest, progress: (String, Float?) -> Unit): String? {
        if (request.prepared.obbFiles.isEmpty()) return null
        if (!request.settings.installObb) return message("root_install_backend_obb_files_were_not_transferred_enable_obb", "OBB не перенесены: включите перенос OBB или экспортируйте ZIP.")
        // Установка APK уже завершена; отмена дополнительного переноса OBB не отменяет этот успех.
        return try {
            request.onObbCopyStarted()
            request.prepared.obbFiles.forEachIndexed { index, obb ->
                currentCoroutineContext().ensureActive()
                progress(message("root_install_backend_root_obb_of", "Root: OBB %1\$s из %2\$s", index + 1, request.prepared.obbFiles.size), null)
                val plan = ObbPolicy.copyPlan(request.userId, request.prepared.metadata.packageName,
                    request.prepared.metadata.versionCode, obb.archivePath.substringAfterLast('/'), obb.size)
                val reporting = TransferProgress(obb.size) { bytes, fraction -> progress(message("root_install_backend_root_obb_mib", "Root: OBB %1\$s · %2\$s МиБ", index + 1, bytes / 1048576), fraction) }
                try {
                    runInterruptible(Dispatchers.IO) {
                        obb.file.inputStream().use { input -> runner.run(listOf("su", "-c", plan.command),
                            ProgressInputStream(input, reporting::report), timeoutSeconds = TransferPolicy.timeoutSeconds(obb.size)).requireSuccess() }
                    }
                } finally {
                    // runInterruptible уже завершил дочерний процесс и снял interrupt с IO-потока.
                    withContext(NonCancellable) { withContext(Dispatchers.IO) {
                        try { runner.run(listOf("su", "-c", plan.cleanupCommand), timeoutSeconds = 15).requireSuccess() }
                        catch (e: Exception) { throw IllegalStateException(message("root_install_backend_could_not_delete_the_temporary_obb_file", "Не удалось удалить временный OBB %1\$s: %2\$s", plan.temporaryPath, nestedMessage(e.message)), e) }
                    } }
                }
            }
            null
        } catch (e: Exception) { obbFailureWarning(e) }
    }
}
