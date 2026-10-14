// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.installer

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
            progress("Root: создание сессии", null)
            val id = runInterruptible(Dispatchers.IO) {
                PmProtocol.sessionId(runner.run(listOf("su", "-c", PmProtocol.create(request.userId, size, request.settings.allowDowngrade)))).also {
                    // ID принадлежит нам ещё до отменяемого возврата из IO.
                    sessionId = it
                    request.onSessionCreated(it)
                }
            }
            request.apks.forEachIndexed { index, apk ->
                currentCoroutineContext().ensureActive()
                progress("Root: запись APK ${index + 1} из ${request.apks.size}", index.toFloat() / request.apks.size)
                runInterruptible(Dispatchers.IO) {
                    apk.file.inputStream().use { input ->
                        runner.run(listOf("su", "-c", PmProtocol.write(id, index, apk.file.length())), input).requireSuccess()
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            progress("Root: установка", null)
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                request.onCommitStarted()
                withContext(NonCancellable) {
                    commitMayHaveRun = true
                    val result = runner.run(listOf("su", "-c", PmProtocol.commit(id)))
                    commitResolved = result.success || result.provenCommitFailure
                    result.requireCommitSuccess(request.prepared.metadata.packageName)
                    committed = true
                    request.onCommitted()
                }
            }
            val warning = copyObb(request, progress)
            return BackendOutcome.Finished(OperationResult(true, "Приложение установлено через Root", request.prepared.metadata.packageName, warning))
        } catch (e: Exception) {
            if (commitMayHaveRun && !commitResolved && e !is UncertainInstallException) {
                throw UncertainInstallException(request.prepared.metadata.packageName,
                    "Ответ Package Manager после начала установки не получен. Результат неизвестен; проверьте приложение.").apply { initCause(e) }
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
        if (!request.settings.installObb) return "OBB не перенесены: включите перенос OBB или экспортируйте ZIP."
        // Установка APK уже завершена; отмена дополнительного переноса OBB не отменяет этот успех.
        return try {
            request.prepared.obbFiles.forEachIndexed { index, obb ->
                currentCoroutineContext().ensureActive()
                progress("Root: OBB ${index + 1} из ${request.prepared.obbFiles.size}", null)
                val command = ObbPolicy.copyCommand(request.userId, request.prepared.metadata.packageName,
                    request.prepared.metadata.versionCode, obb.archivePath.substringAfterLast('/'), obb.size)
                runInterruptible(Dispatchers.IO) {
                    obb.file.inputStream().use { runner.run(listOf("su", "-c", command), it).requireSuccess() }
                }
            }
            null
        } catch (e: Exception) { "APK установлен, но OBB не перенесены: ${e.message ?: "перенос отменён"}. Используйте экспорт ZIP." }
    }
}
