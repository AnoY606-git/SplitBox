// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import dev.splitbox.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Снимок в закрытом кэше для восстановления процесса; перед commit файлы проверяются повторно. */
object PreparedSnapshot {
    fun write(prepared: PreparedPackage, selected: Set<String>, sha256: String?, label: String?) {
        val j = JSONObject().put("sourceName", prepared.sourceName).put("metadata", metadata(prepared.metadata))
            .put("sha256", sha256 ?: JSONObject.NULL).put("label", label ?: JSONObject.NULL)
            .put("warnings", JSONArray(prepared.warnings)).put("selected", JSONArray(selected.toList()))
        j.put("apks", JSONArray().apply { prepared.apks.forEach { e -> put(JSONObject()
            .put("file", e.file.absolutePath).put("archivePath", e.archivePath).put("metadata", metadata(e.metadata))
            .put("size", e.size).put("nativeAbis", JSONArray(e.nativeAbis.toList())).put("selected", e.selected)
            .put("locked", e.locked).put("selectionReason", e.selectionReason)) } })
        j.put("obb", JSONArray().apply { prepared.obbFiles.forEach { e -> put(JSONObject()
            .put("file", e.file.absolutePath).put("archivePath", e.archivePath).put("size", e.size)) } })
        val target = File(prepared.workDir, "checkpoint.json")
        val temporary = File(prepared.workDir, "checkpoint.tmp")
        temporary.outputStream().use { output -> output.write(j.toString().toByteArray(Charsets.UTF_8)); output.fd.sync() }
        check(temporary.renameTo(target)) { "Не удалось сохранить подготовленный пакет" }
    }
    data class Restored(val prepared: PreparedPackage, val selected: Set<String>, val sha256: String?, val label: String?)
    fun read(directory: File, root: File): Restored {
        check(directory.canonicalFile.parentFile == root.canonicalFile) { "Каталог восстановления находится вне кэша" }
        val checkpoint = File(directory, "checkpoint.json")
        check(checkpoint.length() in 1..8388608) { "Снимок состояния отсутствует или повреждён" }
        val j = JSONObject(checkpoint.readText())
        fun safeFile(value: String): File {
            val file = File(value).canonicalFile
            check(file.toPath().startsWith(directory.canonicalFile.toPath()) && file.isFile) { "Временный файл недоступен" }
            return file
        }
        val apkArray = j.getJSONArray("apks")
        check(apkArray.length() in 1..4096) { "Снимок состояния содержит недопустимое количество APK" }
        val apks = (0 until apkArray.length()).map { i -> apkArray.getJSONObject(i).let { e ->
            val file = safeFile(e.getString("file"))
            val size = e.getLong("size")
            check(file.length() == size && size > 0) { "Размер APK изменился" }
            ApkEntry(file, e.getString("archivePath"), readMetadata(e.getJSONObject("metadata")), size,
                strings(e.getJSONArray("nativeAbis")).toSet(), e.getBoolean("selected"), e.getBoolean("locked"), e.getString("selectionReason"))
        } }
        val obbArray = j.getJSONArray("obb")
        check(obbArray.length() <= 1024) { "Снимок состояния содержит слишком много файлов OBB" }
        val obb = (0 until obbArray.length()).map { i -> obbArray.getJSONObject(i).let { e ->
            val file = safeFile(e.getString("file")); val size = e.getLong("size")
            check(file.length() == size && size > 0) { "Размер OBB изменился" }
            ObbEntry(file, e.getString("archivePath"), size)
        } }
        return Restored(PreparedPackage(j.getString("sourceName"), readMetadata(j.getJSONObject("metadata")),
            apks, obb, strings(j.getJSONArray("warnings")), directory), strings(j.getJSONArray("selected")).toSet(),
            j.optString("sha256").takeUnless { it == "null" || it.isEmpty() },
            j.optString("label").takeUnless { it == "null" || it.isEmpty() })
    }
    private fun strings(array: JSONArray) = (0 until array.length()).map { array.getString(it) }
    private fun metadata(m: ApkMetadata) = JSONObject().put("packageName", m.packageName).put("versionCode", m.versionCode)
        .put("versionName", m.versionName).put("minSdk", m.minSdk).put("targetSdk", m.targetSdk)
        .put("splitName", m.splitName ?: JSONObject.NULL).put("configForSplit", m.configForSplit ?: JSONObject.NULL)
        .put("isFeatureSplit", m.isFeatureSplit).put("usesSplits", JSONArray(m.usesSplits))
        .put("requiredSplitTypes", JSONArray(m.requiredSplitTypes.toList())).put("splitTypes", JSONArray(m.splitTypes.toList()))
        .put("requestedPermissions", PermissionSnapshot.write(m.requestedPermissions))
    private fun readMetadata(j: JSONObject) = ApkMetadata(j.getString("packageName"), j.getLong("versionCode"),
        j.getString("versionName"), j.getInt("minSdk"), j.getInt("targetSdk"),
        j.optString("splitName").takeUnless { it == "null" || it.isEmpty() },
        j.optString("configForSplit").takeUnless { it == "null" || it.isEmpty() }, j.getBoolean("isFeatureSplit"),
        strings(j.getJSONArray("usesSplits")), strings(j.getJSONArray("requiredSplitTypes")).toSet(), strings(j.getJSONArray("splitTypes")).toSet(), PermissionSnapshot.read(j))
}
