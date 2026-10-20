// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.installer

import dev.splitbox.core.message
import dev.splitbox.core.nestedMessage

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
                    journal.recordConfirmation(id, confirmation)
                    return
                }
                // Отсутствие подтверждения не доказывает окончательный отказ установки.
                journal.markLaunchFailed(id)
                return
            }
            val success = status == PackageInstaller.STATUS_SUCCESS
            val message = when {
                success -> message("split_box_view_model_app_installed", "Приложение установлено")
                status == Int.MIN_VALUE -> message("install_result_receiver_android_returned_a_result_without_the_required", "Android вернул результат без обязательного статуса")
                status == PackageInstaller.STATUS_FAILURE_ABORTED -> message("install_result_receiver_installation_canceled_by_the_user", "Установка отменена пользователем%1\$s", if (detail.isBlank()) "" else ": $detail")
                else -> message("install_result_receiver_installation_failed_status", "Установка не выполнена (статус %1\$s)%2\$s", status, if (detail.isBlank()) "" else ": $detail")
            }
            journal.recordResult(id, success, message)
    }
}
