// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import dev.splitbox.core.PermissionDeclaration
import org.json.JSONArray
import org.json.JSONObject

/** Отсутствующее или повреждённое поле старого снимка означает неизвестные сведения. */
internal object PermissionSnapshot {
    fun write(permissions: List<PermissionDeclaration>?): Any = permissions?.let { entries ->
        JSONArray().apply {
            entries.forEach { permission ->
                put(JSONObject().put("name", permission.name).put("minSdk", permission.minSdk)
                    .put("maxSdk", permission.maxSdk ?: JSONObject.NULL))
            }
        }
    } ?: JSONObject.NULL

    fun read(metadata: JSONObject): List<PermissionDeclaration>? = runCatching {
        val array = metadata.optJSONArray("requestedPermissions") ?: return null
        check(array.length() <= 65536)
        (0 until array.length()).map { index ->
            val entry = array.getJSONObject(index)
            val name = entry.get("name") as? String ?: error("Имя разрешения не является строкой")
            check(name.isNotBlank())
            val minSdk = exactInt(entry.get("minSdk"))
            val maxSdk = if (entry.isNull("maxSdk")) null else exactInt(entry.get("maxSdk"))
            check(minSdk >= 1 && (maxSdk == null || maxSdk >= 1))
            PermissionDeclaration(name, minSdk, maxSdk)
        }.distinct()
    }.getOrNull()

    private fun exactInt(value: Any): Int {
        check(value is Int || value is Long)
        val number = (value as Number).toLong()
        check(number in Int.MIN_VALUE..Int.MAX_VALUE)
        return number.toInt()
    }
}
