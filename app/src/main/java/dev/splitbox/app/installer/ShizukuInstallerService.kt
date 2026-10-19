// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.installer

import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/** Shizuku запускает этот Binder непосредственно в процессе UserService с правами shell/root. */
class ShizukuInstallerService : IPrivilegedInstaller.Stub() {
    private val runner = ProcessRunner()
    private val sessions = PrivilegedSessionCommands(runner)
    @Volatile private var ownerUid = -1
    @Volatile private var destroying = false
    private var client: IBinder? = null
    private val clientDeath = IBinder.DeathRecipient { Thread { destroy() }.start() }
    @Synchronized override fun attachClient(token: IBinder) {
        check(!destroying && (ownerUid == -1 || ownerUid == Binder.getCallingUid())) { "UserService уже занят другим клиентом" }
        client?.unlinkToDeath(clientDeath, 0)
        ownerUid = Binder.getCallingUid()
        client = token
        token.linkToDeath(clientDeath, 0)
    }
    private fun authorize(userId: Int? = null) {
        check(!destroying && ownerUid >= 0 && Binder.getCallingUid() == ownerUid) { "Клиент UserService недоступен" }
        require(userId == null || userId == ownerUid / 100000) { "Разрешён только текущий пользователь Android" }
    }
    @Synchronized override fun createSession(userId: Int, totalBytes: Long, downgrade: Boolean): Int {
        authorize(userId)
        return sessions.createSession(userId, totalBytes, downgrade)
    }
    @Synchronized override fun writeApk(sessionId: Int, index: Int, size: Long, source: ParcelFileDescriptor, progress: ITransferProgress?) {
        ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
            authorize()
            sessions.writeApk(sessionId, index, size, input) { bytes -> runCatching { progress?.onProgress(bytes) } }
        }
    }
    @Synchronized override fun commitSession(sessionId: Int): String {
        authorize()
        return sessions.commitSession(sessionId)
    }
    @Synchronized override fun abandonSession(sessionId: Int) {
        authorize()
        sessions.abandonSession(sessionId)
    }
    @Synchronized override fun copyObb(userId: Int, packageName: String, version: Long, basename: String, size: Long, source: ParcelFileDescriptor, progress: ITransferProgress?): String {
        return ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
            authorize(userId)
            require(size > 0) { "Размер OBB должен быть положительным" }
            val command = ObbPolicy.copyCommand(userId, packageName, version, basename, size)
            val reporting = TransferProgress(size) { bytes, _ -> runCatching { progress?.onProgress(bytes) } }
            runner.run(listOf("sh", "-c", command), ProgressInputStream(input, reporting::report),
                timeoutSeconds = TransferPolicy.timeoutSeconds(size)).requireSuccess().output
        }
    }
    override fun destroy() {
        destroying = true
        runner.cancelAll()
        synchronized(this) {
            sessions.abandonAll()
            runCatching { client?.unlinkToDeath(clientDeath, 0) }
        }
        kotlin.system.exitProcess(0)
    }
}

/** Ограниченные команды и ownership одного UserService; Binder проверяет UID перед каждым вызовом. */
internal class PrivilegedSessionCommands(private val runner: CommandRunner) {
    private val sessions = ConcurrentHashMap<Int, Long>()
    fun createSession(userId: Int, totalBytes: Long, downgrade: Boolean): Int {
        require(totalBytes > 0) { "Общий размер APK должен быть положительным" }
        val id = PmProtocol.sessionId(runner.run(listOf("sh", "-c", PmProtocol.create(userId, totalBytes, downgrade))))
        sessions[id] = totalBytes
        return id
    }
    fun writeApk(sessionId: Int, index: Int, size: Long, input: InputStream, progress: (Long) -> Unit = {}) {
        check(sessions.containsKey(sessionId)) { "Сессия не принадлежит этому соединению" }
        require(index in 0..4095 && size > 0 && size <= sessions.getValue(sessionId)) { "Недопустимый номер части или размер APK" }
        val reporting = TransferProgress(size) { bytes, _ -> progress(bytes) }
        runner.run(listOf("sh", "-c", PmProtocol.write(sessionId, index, size)), ProgressInputStream(input, reporting::report),
            timeoutSeconds = TransferPolicy.timeoutSeconds(size)).requireSuccess()
    }
    fun commitSession(sessionId: Int): String {
        val size = checkNotNull(sessions.remove(sessionId)) { "Сессия не принадлежит этому соединению" }
        // После отправки commit нельзя abandon по потере Binder: установка могла завершиться.
        val result = runner.run(listOf("sh", "-c", PmProtocol.commit(sessionId)), timeoutSeconds = TransferPolicy.timeoutSeconds(size))
        if (result.provenCommitFailure) sessions[sessionId] = size
        // Отказ передаётся как ответ PM; transport exception не выдаётся за подтверждённую ошибку PM.
        return if (result.exitCode == 0 || result.provenCommitFailure) result.output else ""
    }
    fun abandonSession(sessionId: Int) {
        if (sessions.remove(sessionId) != null) runner.run(listOf("sh", "-c", PmProtocol.abandon(sessionId)), timeoutSeconds = 15).requireSuccess()
    }
    fun abandonAll() {
        sessions.keys.toList().forEach { id -> runCatching { abandonSession(id) } }
    }
}
