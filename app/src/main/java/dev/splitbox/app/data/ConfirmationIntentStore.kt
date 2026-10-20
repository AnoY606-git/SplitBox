// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.util.UUID

/** Android хранит исходный Intent с Binder; на диск попадает только идентификатор токена. */
class ConfirmationIntentStore(context: Context) {
    private val context = context.applicationContext

    fun save(sessionId: Int, intent: Intent): String = synchronized(live) {
        val filter = intent.cloneFilter()
        var generation: String
        var requestCode: Int
        do {
            generation = UUID.randomUUID().toString()
            requestCode = generation.hashCode()
        } while (PendingIntent.getActivity(context, requestCode, filter,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) != null)
        val value = JSONObject().put("version", 1).put("sessionId", sessionId)
            .put("generation", generation).put("requestCode", requestCode)
            .put("filter", filter.toUri(Intent.URI_INTENT_SCHEME)).toString()
        val launch = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(context, requestCode, launch,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Системный реестр использует слабые ссылки: держим токен до завершения операции.
        live[value] = pending
        // Ограничиваем память даже при многократных callbacks и ошибке записи журнала.
        while (live.size > 256) live.remove(live.keys.first())
        value
    }

    fun resolve(sessionId: Int, value: String): PendingIntent? = synchronized(live) {
        val descriptor = descriptor(sessionId, value) ?: return null
        live[value] ?: PendingIntent.getActivity(context, descriptor.first, descriptor.second,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.also { live[value] = it }
    }

    fun cancel(sessionId: Int, value: String) = synchronized(live) {
        if (descriptor(sessionId, value) == null) return
        resolve(sessionId, value)?.cancel()
        live.remove(value)
        Unit
    }

    private fun descriptor(sessionId: Int, value: String): Pair<Int, Intent>? = runCatching {
        val json = JSONObject(value)
        require(json.getInt("version") == 1 && json.getInt("sessionId") == sessionId)
        val generation = json.getString("generation")
        require(UUID.fromString(generation).toString() == generation)
        val requestCode = json.getInt("requestCode")
        require(requestCode == generation.hashCode())
        requestCode to Intent.parseUri(json.getString("filter"), Intent.URI_INTENT_SCHEME)
    }.getOrNull()

    companion object {
        private val live = mutableMapOf<String, PendingIntent>()
    }
}
