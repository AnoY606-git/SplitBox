// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.installer

import dev.splitbox.core.message
import dev.splitbox.core.nestedMessage

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
        check(!destroying && (ownerUid == -1 || ownerUid == Binder.getCallingUid())) { message("shizuku_installer_service_userservice_is_already_in_use_by_another", "UserService уже занят другим клиентом") }
        client?.unlinkToDeath(clientDeath, 0)
        ownerUid = Binder.getCallingUid()
        client = token
        token.linkToDeath(clientDeath, 0)
    }
    private fun authorize(userId: Int? = null) {
        check(!destroying && ownerUid >= 0 && Binder.getCallingUid() == ownerUid) { message("shizuku_installer_service_userservice_client_unavailable", "Клиент UserService недоступен") }
        require(userId == null || userId == ownerUid / 100000) { message("shizuku_installer_service_only_the_current_android_user_is_allowed", "Разрешён только текущий пользователь Android") }
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
            require(size > 0) { message("shizuku_installer_service_the_obb_size_must_be_positive", "Размер OBB должен быть положительным") }
            val plan = ObbPolicy.copyPlan(userId, packageName, version, basename, size)
            val reporting = TransferProgress(size) { bytes, _ -> runCatching { progress?.onProgress(bytes) } }
            copyShizukuObb(runner, plan, ProgressInputStream(input, reporting::report), size).output
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
        require(totalBytes > 0) { message("shizuku_installer_service_the_total_apk_size_must_be_positive", "Общий размер APK должен быть положительным") }
        val id = PmProtocol.sessionId(runner.run(listOf("sh", "-c", PmProtocol.create(userId, totalBytes, downgrade))))
        sessions[id] = totalBytes
        return id
    }
    fun writeApk(sessionId: Int, index: Int, size: Long, input: InputStream, progress: (Long) -> Unit = {}) {
        check(sessions.containsKey(sessionId)) { message("shizuku_installer_service_the_session_does_not_belong_to_this", "Сессия не принадлежит этому соединению") }
        require(index in 0..4095 && size > 0 && size <= sessions.getValue(sessionId)) { message("shizuku_installer_service_invalid_part_number_or_apk_size", "Недопустимый номер части или размер APK") }
        val reporting = TransferProgress(size) { bytes, _ -> progress(bytes) }
        runner.run(listOf("sh", "-c", PmProtocol.write(sessionId, index, size)), ProgressInputStream(input, reporting::report),
            timeoutSeconds = TransferPolicy.timeoutSeconds(size)).requireSuccess()
    }
    fun commitSession(sessionId: Int): String {
        val size = checkNotNull(sessions.remove(sessionId)) { message("shizuku_installer_service_the_session_does_not_belong_to_this", "Сессия не принадлежит этому соединению") }
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

/** Передача OBB в процессе UserService; владельцем временного файла является этот вызов. */
internal fun copyShizukuObb(runner: CommandRunner, plan: ObbCopyPlan, input: InputStream, size: Long): PmResult {
    try { return runner.run(listOf("sh", "-c", plan.command), input, timeoutSeconds = TransferPolicy.timeoutSeconds(size)).requireSuccess() }
    finally {
        // destroy() ждёт synchronized copyObb. Не запускаем новый процесс, который cancelAll мог бы тоже остановить.
        try { java.nio.file.Files.deleteIfExists(java.io.File(plan.temporaryPath).toPath()) }
        catch (e: Exception) { throw IllegalStateException(message("root_install_backend_could_not_delete_the_temporary_obb_file", "Не удалось удалить временный OBB %1\$s: %2\$s", plan.temporaryPath, nestedMessage(e.message)), e) }
    }
}
