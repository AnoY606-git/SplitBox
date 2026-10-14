// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

/** Доставка ожидает подтверждения UI об успешном возврате из startActivity. */
class ConfirmationDelivery {
    private var launched: String? = null
    private var offered: String? = null
    fun offer(token: String): Boolean {
        if (launched == token || offered == token) return false
        offered = token
        return true
    }
    fun acknowledge(token: String, launched: Boolean) {
        if (launched) { this.launched = token; if (offered == token) offered = null }
        else if (this.launched != token && offered == token) offered = null
    }
    fun collectorStopped() { offered = null }
}
data class PendingInstall(
    val sessionId: Int, val packageName: String, val versionName: String, val appLabel: String,
    val sourceName: String, val workDir: String, val createdAt: Long,
    val cleanAfter: Boolean, val saveHistory: Boolean,
    val state: String = "WRITING", val success: Boolean? = null, val message: String = "",
    val confirmation: String? = null, val obbWarning: String? = null
) {
    val blocksNewOperation: Boolean get() = state != "UNKNOWN"
    val historyId: String get() = "system:$sessionId:$createdAt"
    val confirmationToken: String get() = "$sessionId:$createdAt:${confirmation?.hashCode() ?: 0}"
}
/** Ограниченный журнал связей отделяет поздние итоговые ответы от новой активной операции. */
data class SystemSessionLedger(val current: PendingInstall? = null, val retained: List<PendingInstall> = emptyList()) {
    fun isCurrent(expected: PendingInstall): Boolean = current == expected
    fun isLaunchCurrent(token: String): Boolean = current?.let {
        it.confirmation != null && (it.state == "CONFIRMATION" || it.state == "UNKNOWN") && it.confirmationToken == token
    } == true
    fun find(id: Int): PendingInstall? = current?.takeIf { it.sessionId == id } ?: retained.firstOrNull { it.sessionId == id }
    val completedRecords: List<PendingInstall> get() = listOfNotNull(current).plus(retained).filter { it.state == "FINISHED" }
    private fun retain(record: PendingInstall) = (listOf(record) + retained.filterNot { it.sessionId == record.sessionId }).take(100)
    fun begin(record: PendingInstall): SystemSessionLedger = copy(current = record,
        retained = if (current != null && current.sessionId != record.sessionId) retain(current).filterNot { it.sessionId == record.sessionId }
        else retained.filterNot { it.sessionId == record.sessionId })
    fun releaseUnknown(): SystemSessionLedger = current?.takeIf { it.state == "UNKNOWN" }?.let {
        copy(current = null, retained = retain(it))
    } ?: this
    fun missingSession(id: Int): SystemSessionLedger = markUnknown(id,
        "Android больше не показывает сессию; окончательный результат ещё не получен. Можно проверить статус или открыть приложение. Поздний ответ Android обновит историю.", false)
    fun markUnknown(id: Int, message: String, keepConfirmation: Boolean): SystemSessionLedger = find(id)?.let {
        if (it.state == "FINISHED") this else replace(it.copy(state = "UNKNOWN", success = null, message = message,
            confirmation = it.confirmation.takeIf { keepConfirmation }))
    } ?: this
    fun recordConfirmation(id: Int, encodedIntent: String): SystemSessionLedger = find(id)?.let {
        if (it.state == "FINISHED") this else replace(it.copy(state = "CONFIRMATION", confirmation = encodedIntent,
            message = "Подтвердите установку в системном окне"))
    } ?: this
    fun recordResult(id: Int, success: Boolean, message: String): SystemSessionLedger = find(id)?.let {
        if (it.state == "FINISHED") this else replace(it.copy(state = "FINISHED", success = success, message = message, confirmation = null))
    } ?: this
    fun replace(record: PendingInstall): SystemSessionLedger = if (current?.sessionId == record.sessionId) copy(current = record)
        else copy(retained = retained.map { if (it.sessionId == record.sessionId) record else it })
    fun consume(id: Int) = copy(current = current?.takeUnless { it.sessionId == id }, retained = retained.filterNot { it.sessionId == id })
}
