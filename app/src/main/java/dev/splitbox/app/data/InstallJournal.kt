// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import dev.splitbox.core.message
import dev.splitbox.core.nestedMessage

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Parcel
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Общий для процесса снимок журнала; разбор и подтверждённая запись выполняются на IO. */
class InstallJournal private constructor(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    @Volatile private var snapshot = InstallJournalSnapshot()
    @Volatile private var loaded = false
    private lateinit var prefs: SharedPreferences
    private val mutable = MutableStateFlow<PendingInstall?>(null)
    val state: StateFlow<PendingInstall?> = mutable
    private val mutableRetained = MutableStateFlow<List<PendingInstall>>(emptyList())
    val retained: StateFlow<List<PendingInstall>> = mutableRetained
    private val writes = RevisionedSaveQueue<InstallJournalSnapshot>(scope) { persist(it) }
    val persistence: StateFlow<SaveQueueState> = writes.state
    private val initial = scope.async {
        prefs = context.applicationContext.getSharedPreferences("install_journal", Context.MODE_PRIVATE)
        val current = prefs.getString("session", null)?.let { decode(JSONObject(it)) }
        val array = JSONArray(prefs.getString("retained", "[]"))
        require(array.length() <= 100) { message("install_journal_the_system_log_contains_too_many_records", "Системный журнал содержит слишком много записей") }
        val retained = (0 until array.length()).map { decode(array.getJSONObject(it)) }
        val source = prefs.getString("resumeSource", null)
        val permission = source?.let { Triple(it, prefs.getString("resumeName", message("install_journal_archive", "Архив"))!!,
            prefs.getString("resumeDirectory", "")!!) }
        synchronized(lock) {
            snapshot = InstallJournalSnapshot(SystemSessionLedger(current, retained), prefs.getString("preparedDirectory", null), permission)
            loaded = true
            publish(snapshot.ledger)
        }
    }
    val isReady: Boolean get() = loaded
    suspend fun awaitReady() { initial.await() }
    /** Подтверждение записи обязательно до передачи управления Android. */
    suspend fun flush() { awaitReady(); writes.flush() }
    fun retrySave() = writes.retry()
    fun read(): PendingInstall? = snapshot.ledger.current
    fun isCurrent(expected: PendingInstall): Boolean = snapshot.ledger.isCurrent(expected)
    fun isLaunchCurrent(token: String): Boolean = snapshot.ledger.isLaunchCurrent(token)
    fun find(sessionId: Int): PendingInstall? = snapshot.ledger.find(sessionId)
    private fun publish(ledger: SystemSessionLedger) { mutable.value = ledger.current; mutableRetained.value = ledger.retained }
    private fun update(transform: (InstallJournalSnapshot) -> InstallJournalSnapshot) = synchronized(lock) {
        check(loaded) { message("install_journal_the_installation_log_is_still_loading", "Журнал установки ещё загружается") }
        val next = transform(snapshot)
        if (next != snapshot) {
            snapshot = next
            writes.submit(next)
            publish(next.ledger)
        }
    }
    private fun mutate(transform: (SystemSessionLedger) -> SystemSessionLedger) = update { it.copy(ledger = transform(it.ledger)) }
    private fun persist(value: InstallJournalSnapshot) {
        prefs.commitChecked({ message("install_journal_could_not_save_the_installation_log_free", "Не удалось сохранить журнал установки. Освободите место и повторите сохранение.") }) {
            if (value.ledger.current == null) remove("session")
            else putString("session", encode(value.ledger.current).toString())
            putString("retained", JSONArray().apply { value.ledger.retained.forEach { put(encode(it)) } }.toString())
            putString("preparedDirectory", value.prepared)
            putString("resumeSource", value.permission?.first).putString("resumeName", value.permission?.second)
                .putString("resumeDirectory", value.permission?.third)
        }
    }
    fun write(value: PendingInstall) = mutate { ledger ->
        val previous = ledger.find(value.sessionId)
        when { previous == null -> ledger.begin(value); previous.state == "FINISHED" -> ledger; else -> ledger.replace(value) }
    }
    fun markMissing(sessionId: Int) = mutate { it.missingSession(sessionId) }
    fun markLaunchFailed(sessionId: Int) = mutate { it.markUnknown(sessionId,
        message("install_journal_the_system_dialog_did_not_open_tap", "Системное окно не открылось. Нажмите «Проверить статус», чтобы повторить запуск, или выберите другой файл. Результат установки пока не подтверждён."), true) }
    fun recordConfirmation(sessionId: Int, encodedIntent: String) = mutate { it.recordConfirmation(sessionId, encodedIntent) }
    fun recordResult(sessionId: Int, success: Boolean, message: String) = mutate { it.recordResult(sessionId, success, message) }
    fun consume(sessionId: Int) = mutate { it.consume(sessionId) }
    fun detachUnknown() = mutate { it.releaseUnknown() }
    fun clear() { read()?.let { consume(it.sessionId) } }
    fun preparedDirectory(): String? = snapshot.prepared
    fun savePreparedDirectory(directory: String) {
        require(directory.length <= 4096) { message("install_journal_the_prepared_package_path_is_too_long", "Путь к готовому пакету слишком длинный") }
        update { it.copy(prepared = directory) }
    }
    fun clearPreparedDirectory(expected: String? = null) = update {
        if (expected == null || it.prepared == expected) it.copy(prepared = null) else it
    }
    fun saveAwaitingPermission(source: String, name: String, workDir: String) = update {
        it.copy(permission = Triple(source, name, workDir))
    }
    fun returnPermissionToReady(directory: String) = update { it.returnPermissionToReady(directory) }
    fun awaitingPermission(): Triple<String, String, String>? = snapshot.permission
    fun clearAwaitingPermission() = update { it.copy(permission = null) }
    companion object {
        @Volatile private var instance: InstallJournal? = null
        fun get(context: Context): InstallJournal = instance ?: synchronized(this) {
            instance ?: InstallJournal(context).also { instance = it }
        }
        private fun decode(j: JSONObject) = PendingInstall(j.getInt("sessionId"), j.getString("packageName"), j.getString("versionName"),
            j.getString("appLabel"), j.getString("sourceName"), j.getString("workDir"), j.getLong("createdAt"),
            j.getBoolean("cleanAfter"), j.getBoolean("saveHistory"), j.getString("state"),
            if (j.isNull("success")) null else j.getBoolean("success"), j.optString("message"),
            if (j.isNull("confirmation")) null else j.getString("confirmation"),
            if (j.isNull("obbWarning")) null else j.getString("obbWarning"))
        private fun encode(value: PendingInstall) = JSONObject().put("sessionId", value.sessionId).put("packageName", value.packageName)
            .put("versionName", value.versionName).put("appLabel", value.appLabel).put("sourceName", value.sourceName)
            .put("workDir", value.workDir).put("createdAt", value.createdAt).put("cleanAfter", value.cleanAfter)
            .put("saveHistory", value.saveHistory).put("state", value.state).put("success", value.success ?: JSONObject.NULL)
            .put("message", value.message).put("confirmation", value.confirmation ?: JSONObject.NULL)
            .put("obbWarning", value.obbWarning ?: JSONObject.NULL)
        fun encodeIntent(intent: Intent): String {
            val parcel = Parcel.obtain()
            return try { intent.writeToParcel(parcel, 0); Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP) }
            finally { parcel.recycle() }
        }
        fun decodeIntent(value: String): Intent {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            val parcel = Parcel.obtain()
            return try { parcel.unmarshall(bytes, 0, bytes.size); parcel.setDataPosition(0); Intent.CREATOR.createFromParcel(parcel) }
            finally { parcel.recycle() }
        }
    }
}
