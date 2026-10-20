// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.installer

import dev.splitbox.core.message
import dev.splitbox.core.nestedMessage

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
    fun requestPermission(requestCode: Int = 901) { check(isRunning()) { message("shizuku_install_backend_start_shizuku_and_return_to_splitbox", "Запустите Shizuku и вернитесь в SplitBox") }; Shizuku.requestPermission(requestCode) }
    private class Connection(val args: Shizuku.UserServiceArgs) : ServiceConnection {
        val ready = CompletableDeferred<IPrivilegedInstaller>()
        val loss = CompletableDeferred<Unit>()
        var binder: IBinder? = null
        val death = IBinder.DeathRecipient { loss.complete(Unit); ready.completeExceptionally(IOException(message("shizuku_install_backend_shizuku_connection_lost", "Соединение Shizuku потеряно"))) }
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = service
            try { service.linkToDeath(death, 0); ready.complete(IPrivilegedInstaller.Stub.asInterface(service)) }
            catch (e: Exception) { ready.completeExceptionally(e) }
        }
        override fun onServiceDisconnected(name: ComponentName) { loss.complete(Unit); ready.completeExceptionally(IOException(message("shizuku_install_backend_shizuku_disconnected", "Shizuku отключился"))) }
    }
    override suspend fun install(request: InstallRequest, progress: (String, Float?) -> Unit): BackendOutcome {
        check(isGranted()) { message("shizuku_install_backend_shizuku_permission_not_granted_start_shizuku_and", "Нет разрешения Shizuku. Запустите Shizuku и разрешите доступ.") }
        val args = Shizuku.UserServiceArgs(ComponentName(context, ShizukuInstallerService::class.java))
            .daemon(false).processNameSuffix("installer").debuggable(false).version(2).tag("splitbox-installer")
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
            progress(message("shizuku_install_backend_shizuku_creating_session", "Shizuku: создание сессии"), null)
            val id = withContext(NonCancellable + Dispatchers.IO) {
                // Binder нельзя отменить после create: фиксируем ownership до возврата в отменяемый контекст.
                remoteCall(loss = connection.loss) { remote.createSession(request.userId, size, request.settings.allowDowngrade) }.also {
                    sessionId = it
                    request.onSessionCreated(it)
                }
            }
            var written = 0L
            val reporting = TransferProgress(size) { bytes, fraction -> progress(message("shizuku_install_backend_shizuku_writing_apk_mib", "Shizuku: запись APK · %1\$s МиБ", bytes / 1048576), fraction) }
            request.apks.forEachIndexed { index, entry ->
                currentCoroutineContext().ensureActive()
                progress(message("shizuku_install_backend_shizuku_writing_apk_of", "Shizuku: запись APK %1\$s из %2\$s", index + 1, request.apks.size), (written.toDouble() / size).toFloat())
                val offset = written
                val receiving = java.util.concurrent.atomic.AtomicBoolean(true)
                val callback = object : ITransferProgress.Stub() {
                    override fun onProgress(bytes: Long) { if (receiving.get() && bytes in 0..entry.file.length()) reporting.report(offset + bytes) }
                }
                try {
                    ParcelFileDescriptor.open(entry.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        remoteCall(TransferPolicy.binderTimeoutMs(entry.file.length()), connection.loss) {
                            remote.writeApk(id, index, entry.file.length(), descriptor, callback)
                        }
                    }
                    written = Math.addExact(written, entry.file.length())
                    reporting.report(written)
                } finally { receiving.set(false) }
            }
            currentCoroutineContext().ensureActive()
            progress(message("shizuku_install_backend_shizuku_installing", "Shizuku: установка"), null)
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                request.onCommitStarted()
                withContext(NonCancellable) {
                    commitMayHaveRun = true
                    val output = remoteCall(TransferPolicy.binderTimeoutMs(size), connection.loss) { remote.commitSession(id) }
                    val result = PmResult(0, output)
                    commitResolved = result.success || result.provenCommitFailure
                    result.requireCommitSuccess(request.prepared.metadata.packageName)
                    committed = true
                    request.onCommitted()
                }
            }
            val warning = copyObb(request, remote, connection.loss, progress)
            return BackendOutcome.Finished(OperationResult(true, message("shizuku_install_backend_app_installed_via_shizuku", "Приложение установлено через Shizuku"), request.prepared.metadata.packageName, warning))
        } catch (e: Exception) {
            if (commitMayHaveRun && !commitResolved && e !is UncertainInstallException) {
                throw UncertainInstallException(request.prepared.metadata.packageName,
                    message("shizuku_install_backend_no_response_from_shizuku_was_received_after", "Ответ Shizuku после начала установки не получен. Результат неизвестен; проверьте приложение.")).apply { initCause(e) }
            }
            if (e is TimeoutCancellationException) throw IOException(message("shizuku_install_backend_shizuku_did_not_respond_in_time_check", "Shizuku не ответил за отведённое время. Проверьте службу."), e)
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
        if (!request.settings.installObb) return message("root_install_backend_obb_files_were_not_transferred_enable_obb", "OBB не перенесены: включите перенос OBB или экспортируйте ZIP.")
        return try {
            request.onObbCopyStarted()
            request.prepared.obbFiles.forEachIndexed { index, entry ->
                currentCoroutineContext().ensureActive()
                progress(message("shizuku_install_backend_shizuku_obb_of", "Shizuku: OBB %1\$s из %2\$s", index + 1, request.prepared.obbFiles.size), null)
                ObbPolicy.destination(request.userId, request.prepared.metadata.packageName, request.prepared.metadata.versionCode, entry.archivePath.substringAfterLast('/'))
                val receiving = java.util.concurrent.atomic.AtomicBoolean(true)
                val reporting = TransferProgress(entry.size) { bytes, fraction -> progress(message("shizuku_install_backend_shizuku_obb_mib", "Shizuku: OBB %1\$s · %2\$s МиБ", index + 1, bytes / 1048576), fraction) }
                val callback = object : ITransferProgress.Stub() {
                    override fun onProgress(bytes: Long) { if (receiving.get() && bytes in 0..entry.size) reporting.report(bytes) }
                }
                try {
                    ParcelFileDescriptor.open(entry.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        remoteCall(TransferPolicy.binderTimeoutMs(entry.size), loss) { remote.copyObb(request.userId, request.prepared.metadata.packageName,
                            request.prepared.metadata.versionCode, entry.archivePath.substringAfterLast('/'), entry.file.length(), descriptor, callback) }
                    }
                    reporting.report(entry.size)
                } finally { receiving.set(false) }
            }
            null
        } catch (e: Exception) { obbFailureWarning(e) }
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
                loss.onAwait { throw IOException(message("shizuku_install_backend_shizuku_connection_lost_start_the_service_and", "Соединение Shizuku потеряно. Запустите службу и повторите установку.")) }
            }
        } finally { thread.interrupt() }
    }
}
