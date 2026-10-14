// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.installer

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import dev.splitbox.app.model.OperationResult
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import java.io.IOException
import kotlinx.coroutines.selects.select

class ShizukuInstallBackend(private val context: Context) : InstallBackend {
    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false)
    fun isGranted(): Boolean = isRunning() && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    fun requestPermission() { check(isRunning()) { "Запустите Shizuku и вернитесь в SplitBox" }; Shizuku.requestPermission(901) }
    private class Connection(val args: Shizuku.UserServiceArgs) : ServiceConnection {
        val ready = CompletableDeferred<IPrivilegedInstaller>()
        val loss = CompletableDeferred<Unit>()
        var binder: IBinder? = null
        val death = IBinder.DeathRecipient { loss.complete(Unit); ready.completeExceptionally(IOException("Соединение Shizuku потеряно")) }
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = service
            try { service.linkToDeath(death, 0); ready.complete(IPrivilegedInstaller.Stub.asInterface(service)) }
            catch (e: Exception) { ready.completeExceptionally(e) }
        }
        override fun onServiceDisconnected(name: ComponentName) { loss.complete(Unit); ready.completeExceptionally(IOException("Shizuku отключился")) }
    }
    override suspend fun install(request: InstallRequest, progress: (String, Float?) -> Unit): BackendOutcome {
        check(isGranted()) { "Нет разрешения Shizuku. Запустите Shizuku и разрешите доступ." }
        val args = Shizuku.UserServiceArgs(ComponentName(context, ShizukuInstallerService::class.java))
            .daemon(false).processNameSuffix("installer").debuggable(false).version(1).tag("splitbox-installer")
        val connection = Connection(args)
        val clientToken = Binder()
        val globalDeath = Shizuku.OnBinderDeadListener { connection.loss.complete(Unit) }
        Shizuku.addBinderDeadListener(globalDeath)
        var service: IPrivilegedInstaller? = null
        var sessionId: Int? = null
        var committed = false
        var commitMayHaveRun = false
        var commitResolved = false
        try {
            withContext(Dispatchers.Main) { Shizuku.bindUserService(args, connection) }
            val remote = withTimeout(15000) { connection.ready.await() }
            service = remote
            remoteCall(loss = connection.loss) { remote.attachClient(clientToken) }
            val size = request.apks.fold(0L) { sum, entry -> Math.addExact(sum, entry.file.length()) }
            progress("Shizuku: создание сессии", null)
            val id = withContext(NonCancellable + Dispatchers.IO) {
                // Binder нельзя отменить после create: фиксируем ownership до возврата в отменяемый контекст.
                remoteCall(loss = connection.loss) { remote.createSession(request.userId, size, request.settings.allowDowngrade) }.also {
                    sessionId = it
                    request.onSessionCreated(it)
                }
            }
            request.apks.forEachIndexed { index, entry ->
                currentCoroutineContext().ensureActive()
                progress("Shizuku: запись APK ${index + 1} из ${request.apks.size}", index.toFloat() / request.apks.size)
                ParcelFileDescriptor.open(entry.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    remoteCall(loss = connection.loss) { remote.writeApk(id, index, entry.file.length(), descriptor) }
                }
            }
            currentCoroutineContext().ensureActive()
            progress("Shizuku: установка", null)
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                request.onCommitStarted()
                withContext(NonCancellable) {
                    commitMayHaveRun = true
                    val output = remoteCall(loss = connection.loss) { remote.commitSession(id) }
                    val result = PmResult(0, output)
                    commitResolved = result.success || result.provenCommitFailure
                    result.requireCommitSuccess(request.prepared.metadata.packageName)
                    committed = true
                    request.onCommitted()
                }
            }
            val warning = copyObb(request, remote, connection.loss, progress)
            return BackendOutcome.Finished(OperationResult(true, "Приложение установлено через Shizuku", request.prepared.metadata.packageName, warning))
        } catch (e: Exception) {
            if (commitMayHaveRun && !commitResolved && e !is UncertainInstallException) {
                throw UncertainInstallException(request.prepared.metadata.packageName,
                    "Ответ Shizuku после начала установки не получен. Результат неизвестен; проверьте приложение.").apply { initCause(e) }
            }
            if (e is TimeoutCancellationException) throw IOException("Shizuku не ответил за отведённое время. Проверьте службу.", e)
            throw e
        } finally {
            withContext(NonCancellable) {
                Shizuku.removeBinderDeadListener(globalDeath)
                val closingService = service
                val abandonedId = sessionId
                if (!committed && (!commitMayHaveRun || commitResolved) && abandonedId != null && closingService != null) runCatching { remoteCall(20000) { closingService.abandonSession(abandonedId) } }
                connection.binder?.let { runCatching { it.unlinkToDeath(connection.death, 0) } }
                withContext(Dispatchers.Main) { runCatching { Shizuku.unbindUserService(args, connection, true) } }
            }
        }
    }
    private suspend fun copyObb(request: InstallRequest, remote: IPrivilegedInstaller, loss: Deferred<Unit>, progress: (String, Float?) -> Unit): String? {
        if (request.prepared.obbFiles.isEmpty()) return null
        if (!request.settings.installObb) return "OBB не перенесены: включите перенос OBB или экспортируйте ZIP."
        return try {
            request.prepared.obbFiles.forEachIndexed { index, entry ->
                currentCoroutineContext().ensureActive()
                progress("Shizuku: OBB ${index + 1} из ${request.prepared.obbFiles.size}", null)
                ObbPolicy.destination(request.userId, request.prepared.metadata.packageName, request.prepared.metadata.versionCode, entry.archivePath.substringAfterLast('/'))
                ParcelFileDescriptor.open(entry.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    remoteCall(loss = loss) { remote.copyObb(request.userId, request.prepared.metadata.packageName,
                        request.prepared.metadata.versionCode, entry.archivePath.substringAfterLast('/'), entry.file.length(), descriptor) }
                }
            }
            null
        } catch (e: Exception) { "APK установлен, но OBB не перенесены: ${e.message ?: "перенос отменён"}. Используйте экспорт ZIP." }
    }
    /** Вызовы Binder нельзя прервать; ждём ответ, потерю соединения или истечение конечного срока. */
    private suspend fun <T> remoteCall(timeoutMs: Long = 150000, loss: Deferred<Unit>? = null, operation: () -> T): T = withTimeout(timeoutMs) {
        val response = CompletableDeferred<T>()
        val thread = Thread({
            try { response.complete(operation()) }
            catch (e: Exception) { response.completeExceptionally(e) }
        }, "splitbox-binder").apply { isDaemon = true; start() }
        try {
            if (loss == null) response.await()
            else select {
                response.onAwait { it }
                loss.onAwait { throw IOException("Соединение Shizuku потеряно. Запустите службу и повторите установку.") }
            }
        } finally { thread.interrupt() }
    }
}
