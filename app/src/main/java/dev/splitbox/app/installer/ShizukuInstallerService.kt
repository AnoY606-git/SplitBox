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
    @Synchronized override fun writeApk(sessionId: Int, index: Int, size: Long, source: ParcelFileDescriptor) {
        ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
            authorize()
            sessions.writeApk(sessionId, index, size, input)
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
    @Synchronized override fun copyObb(userId: Int, packageName: String, version: Long, basename: String, size: Long, source: ParcelFileDescriptor): String {
        return ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
            authorize(userId)
            require(size in 1..(4L * 1024 * 1024 * 1024)) { "Размер OBB должен быть больше нуля и не превышать 4 ГиБ" }
            val command = ObbPolicy.copyCommand(userId, packageName, version, basename, size)
            runner.run(listOf("sh", "-c", command), input).requireSuccess().output
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
    private val sessions = ConcurrentHashMap.newKeySet<Int>()
    fun createSession(userId: Int, totalBytes: Long, downgrade: Boolean): Int {
        require(totalBytes in 1..(4L * 1024 * 1024 * 1024)) { "Общий размер APK должен быть больше нуля и не превышать 4 ГиБ" }
        val id = PmProtocol.sessionId(runner.run(listOf("sh", "-c", PmProtocol.create(userId, totalBytes, downgrade))))
        sessions.add(id)
        return id
    }
    fun writeApk(sessionId: Int, index: Int, size: Long, input: InputStream) {
        check(sessionId in sessions) { "Сессия не принадлежит этому соединению" }
        require(index in 0..4095 && size in 1..(4L * 1024 * 1024 * 1024)) { "Недопустимый номер части или размер APK" }
        runner.run(listOf("sh", "-c", PmProtocol.write(sessionId, index, size)), input).requireSuccess()
    }
    fun commitSession(sessionId: Int): String {
        check(sessions.remove(sessionId)) { "Сессия не принадлежит этому соединению" }
        // После отправки commit нельзя abandon по потере Binder: установка могла завершиться.
        val result = runner.run(listOf("sh", "-c", PmProtocol.commit(sessionId)))
        if (result.provenCommitFailure) sessions.add(sessionId)
        // Отказ передаётся как ответ PM; transport exception не выдаётся за подтверждённую ошибку PM.
        return if (result.exitCode == 0 || result.provenCommitFailure) result.output else ""
    }
    fun abandonSession(sessionId: Int) {
        if (sessions.remove(sessionId)) runner.run(listOf("sh", "-c", PmProtocol.abandon(sessionId)), timeoutSeconds = 15).requireSuccess()
    }
    fun abandonAll() {
        sessions.toList().forEach { id -> runCatching { abandonSession(id) } }
    }
}
