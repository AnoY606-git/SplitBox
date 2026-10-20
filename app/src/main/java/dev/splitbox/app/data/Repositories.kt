// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import dev.splitbox.core.message
import dev.splitbox.core.nestedMessage

import android.app.LocaleManager
import android.os.LocaleList
import android.content.Context
import dev.splitbox.app.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

class SettingsRepository(context: Context) {
    private val localeManager = context.getSystemService(LocaleManager::class.java)
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val migratedDefault = !prefs.getBoolean("sizePolicyV2", false) && prefs.getInt("maxExpandedGiB", 0) == 2
    val sizeMigrationNotice: String? = if (migratedDefault)
        message("repositories_the_previous_default_limit_of_2_gib", "Прежний лимит по умолчанию 2 ГиБ заменён автоматическим ограничением по свободному месту. Если вы выбирали 2 ГиБ вручную, верните этот лимит в настройках.") else null
    private val mutable = MutableStateFlow(read())
    init {
        if (!prefs.getBoolean("sizePolicyV2", false)) prefs.edit()
            .putInt("maxExpandedGiB", mutable.value.maxExpandedGiB).putBoolean("sizePolicyV2", true).apply()
    }
    val state: StateFlow<AppSettings> = mutable
    private fun read() = AppSettings(
        language = currentLanguage(),
        backend = runCatching { BackendMode.valueOf(prefs.getString("backend", "SYSTEM")!!) }.getOrDefault(BackendMode.SYSTEM),
        theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM),
        dynamicColors = prefs.getBoolean("dynamicColors", false),
        autoSelectSplits = prefs.getBoolean("autoSelectSplits", true),
        verifySignatures = prefs.getBoolean("verifySignatures", true),
        computeSha256 = prefs.getBoolean("computeSha256", true),
        maxExpandedGiB = PackageSizePolicy.restoredLimit(if (prefs.contains("maxExpandedGiB")) prefs.getInt("maxExpandedGiB", 0) else null, prefs.getBoolean("sizePolicyV2", false)),
        cleanAfterInstall = prefs.getBoolean("cleanAfterInstall", true),
        saveHistory = prefs.getBoolean("saveHistory", true), haptics = prefs.getBoolean("haptics", true),
        keepScreenOn = prefs.getBoolean("keepScreenOn", true), installObb = prefs.getBoolean("installObb", false),
        allowDowngrade = prefs.getBoolean("allowDowngrade", false)
    )
    private fun currentLanguage() = AppLanguage.fromLanguageTags(localeManager.applicationLocales.toLanguageTags())
    fun refreshLanguage() { mutable.value = mutable.value.copy(language = currentLanguage()) }
    fun update(value: AppSettings) {
        require(value.maxExpandedGiB in PackageSizePolicy.choicesGiB) { message("package_size_policy_invalid_package_size_limit", "Некорректный лимит размера пакета") }
        prefs.edit().putString("backend", value.backend.name).putString("theme", value.theme.name)
            .putBoolean("dynamicColors", value.dynamicColors).putBoolean("autoSelectSplits", value.autoSelectSplits)
            .putBoolean("verifySignatures", value.verifySignatures).putBoolean("computeSha256", value.computeSha256)
            .putInt("maxExpandedGiB", value.maxExpandedGiB).putBoolean("sizePolicyV2", true).putBoolean("cleanAfterInstall", value.cleanAfterInstall)
            .putBoolean("saveHistory", value.saveHistory).putBoolean("haptics", value.haptics)
            .putBoolean("keepScreenOn", value.keepScreenOn).putBoolean("installObb", value.installObb)
            .putBoolean("allowDowngrade", value.allowDowngrade).apply()
        mutable.value = value
        if (value.language != currentLanguage()) {
            localeManager.applicationLocales = LocaleList.forLanguageTags(value.language.languageTags)
        }
    }
}
class HistoryRepository(context: Context) {
    private val prefs = context.getSharedPreferences("history", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val state: StateFlow<List<HistoryEntry>> = mutable
    private fun read(): List<HistoryEntry> = runCatching {
        val array = JSONArray(prefs.getString("entries", "[]"))
        (0 until array.length()).map { i -> array.getJSONObject(i).let { j ->
            HistoryEntry(j.getString("id"), j.getLong("timestamp"), j.getString("appLabel"),
                j.getString("packageName"), j.getString("versionName"), j.getString("fileName"),
                BackendMode.valueOf(j.getString("backend")), if (j.isNull("success")) null else j.getBoolean("success"), j.getString("message"))
        } }.take(100)
    }.getOrDefault(emptyList())
    @Synchronized fun add(entry: HistoryEntry) {
        val updated = (listOf(entry) + mutable.value.filterNot { it.id == entry.id }).take(100)
        save(updated)
    }
    @Synchronized fun clear() = save(emptyList())
    private fun save(values: List<HistoryEntry>) {
        val array = JSONArray()
        values.forEach { e -> array.put(JSONObject().put("id", e.id).put("timestamp", e.timestamp)
            .put("appLabel", e.appLabel).put("packageName", e.packageName).put("versionName", e.versionName)
            .put("fileName", e.fileName).put("backend", e.backend.name).put("success", e.success ?: JSONObject.NULL).put("message", e.message)) }
        check(prefs.edit().putString("entries", array.toString()).commit()) { message("repositories_could_not_save_installation_history", "Не удалось сохранить историю установки") }
        mutable.value = values
    }
}
