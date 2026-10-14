// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.installer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import dev.splitbox.app.data.InstallJournal
import kotlinx.coroutines.*
import android.util.Log

/** Непубличный приёмник сохраняет результат на диске, не запуская Activity из фона. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
          try {
            withTimeout(8_000) {
            val journal = InstallJournal.get(context)
            journal.awaitReady()
            handle(context, intent, journal)
            journal.flush()
            }
          } catch (e: Exception) {
            Log.e("SplitBox", "Не удалось сохранить результат Android", e)
          } finally { pending.finish() }
        }
    }
    private fun handle(context: Context, intent: Intent, journal: InstallJournal) {
            val id = intent.getIntExtra("splitbox_session", -1)
            val stored = journal.find(id) ?: return
            if (stored.state == "FINISHED") return
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
            val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.take(3000).orEmpty()
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                val confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                if (confirmation != null) {
                    val encoded = runCatching { InstallJournal.encodeIntent(confirmation) }.getOrNull()
                    if (encoded != null) {
                        journal.recordConfirmation(id, encoded)
                        return
                    }
                }
                journal.recordResult(id, false, "Android не передал пригодное подтверждение установки")
                runCatching { context.packageManager.packageInstaller.abandonSession(id) }
                return
            }
            val success = status == PackageInstaller.STATUS_SUCCESS
            val message = when {
                success -> "Приложение установлено"
                status == Int.MIN_VALUE -> "Android вернул результат без обязательного статуса"
                status == PackageInstaller.STATUS_FAILURE_ABORTED -> "Установка отменена пользователем${if (detail.isBlank()) "" else ": $detail"}"
                else -> "Установка не выполнена (статус $status)${if (detail.isBlank()) "" else ": $detail"}"
            }
            journal.recordResult(id, success, message)
    }
}
