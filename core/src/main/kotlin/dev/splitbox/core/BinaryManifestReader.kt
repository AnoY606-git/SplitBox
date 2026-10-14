// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

/** Читает скомпилированный XML Android без загрузки классов Android и разрешения ресурсов приложения. */
class BinaryManifestReader {
    fun read(bytes: ByteArray): ApkMetadata {
        try { return parse(bytes) } catch (e: PreparationException) { throw e }
        catch (_: Exception) { throw PreparationException("Повреждённый двоичный AndroidManifest.xml") }
    }
    private fun parse(bytes: ByteArray): ApkMetadata {
        if (bytes.size > MAX_METADATA_BYTES) fail("Манифест превышает 4 МиБ")
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun u16(at: Int): Int = data.getShort(at).toInt() and 0xffff
        fun i32(at: Int): Int = data.getInt(at)
        fun range(at: Int, length: Int, end: Int = bytes.size) {
            if (at < 0 || length < 0 || at > end - length) fail("Обрезанный двоичный манифест")
        }
        range(0, 8)
        if (u16(0) != 3 || u16(2) < 8 || i32(4) != bytes.size) fail("Ожидается двоичный XML Android")
        var strings: List<String>? = null
        var metadata: ApkMetadata? = null
        var minSdk = 1; var targetSdk: Int? = null
        val uses = mutableListOf<String>()
        val permissions = mutableListOf<PermissionDeclaration>()
        val stack = mutableListOf<String>()
        var position = u16(2)
        while (position < bytes.size) {
            range(position, 8)
            val type = u16(position); val header = u16(position + 2); val size = i32(position + 4)
            if (header < 8 || size < header) fail("Некорректный блок манифеста")
            range(position, size)
            val end = position + size
            if (type == 1) {
                if (strings != null || metadata != null) fail("Повторный пул строк")
                if (header < 28) fail("Некорректный пул строк")
                val count = i32(position + 8); val styleCount = i32(position + 12)
                val flags = i32(position + 16); val start = i32(position + 20); val stylesStart = i32(position + 24)
                if (count < 0 || count > 65536 || styleCount < 0 || styleCount > 65536) fail("Слишком большой пул строк")
                range(position + header, (count + styleCount) * 4, end)
                if (start < header + (count + styleCount) * 4 || start > size) fail("Некорректные смещения пула строк")
                val stringsEnd = if (stylesStart == 0) end else {
                    if (stylesStart < start || stylesStart > size) fail("Некорректное смещение стилей")
                    position + stylesStart
                }
                val utf8 = flags and 0x100 != 0
                var decodedCharacters = 0
                val decodedByOffset = mutableMapOf<Int, String>()
                strings = (0 until count).map { index ->
                    val relative = i32(position + header + index * 4)
                    if (relative < 0 || relative >= stringsEnd - position - start) fail("Некорректное смещение строки")
                    val decoded = decodedByOffset.getOrPut(relative) {
                        var cursor = position + start + relative
                        fun length8(): Int {
                            range(cursor, 1, stringsEnd); val first = bytes[cursor++].toInt() and 0xff
                            return if (first and 0x80 == 0) first else {
                                range(cursor, 1, stringsEnd); ((first and 0x7f) shl 8) or (bytes[cursor++].toInt() and 0xff)
                            }
                        }
                        fun length16(): Int {
                            range(cursor, 2, stringsEnd); val first = u16(cursor); cursor += 2
                            return if (first and 0x8000 == 0) first else {
                                range(cursor, 2, stringsEnd); (((first and 0x7fff) shl 16) or u16(cursor)).also { cursor += 2 }
                            }
                        }
                        if (utf8) {
                            val chars = length8(); val length = length8()
                            range(cursor, length + 1, stringsEnd)
                            if (bytes[cursor + length].toInt() != 0) fail("Пул строк без завершающего нулевого символа")
                            val value = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, cursor, length)).toString()
                            if (value.length != chars) fail("Некорректная строка UTF-8 манифеста")
                            value
                        } else {
                            val length = length16()
                            if (length > MAX_METADATA_BYTES / 2) fail("Слишком длинная строка манифеста")
                            range(cursor, length * 2 + 2, stringsEnd)
                            if (u16(cursor + length * 2) != 0) fail("Пул строк без завершающего нулевого символа")
                            Charsets.UTF_16LE.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(bytes, cursor, length * 2)).toString()
                        }
                    }
                    decodedCharacters += decoded.length
                    if (decodedCharacters > MAX_METADATA_BYTES) fail("Суммарный объём строк манифеста превышает лимит")
                    decoded
                }
            } else if (type == 0x102) {
                val pool = strings ?: fail("Манифест без пула строк")
                fun string(index: Int): String = pool.getOrNull(index) ?: fail("Некорректный индекс строки")
                if (header < 16) fail("Некорректный узел XML")
                range(position + header, 20, end)
                val element = string(i32(position + header + 4))
                if (stack.isEmpty() && (element != "manifest" || metadata != null)) fail("Ожидается единственный корневой элемент манифеста")
                val attributeStart = u16(position + header + 8); val attributeSize = u16(position + header + 10)
                val count = u16(position + header + 12)
                if (attributeStart < 20 || attributeSize < 20 || count > 1024) fail("Некорректные атрибуты XML")
                val attributePosition = position + header + attributeStart
                range(attributePosition, count * attributeSize, end)
                data class Value(val type: Int, val value: Int, val raw: String?)
                val attrs = mutableMapOf<Pair<String, String>, Value>()
                repeat(count) { index ->
                    val at = attributePosition + index * attributeSize
                    val ns = i32(at).let { if (it == -1) "" else string(it) }
                    val name = string(i32(at + 4)); val rawIndex = i32(at + 8)
                    if (u16(at + 12) != 8 || bytes[at + 14].toInt() != 0) fail("Некорректное типизированное значение")
                    val value = Value(bytes[at + 15].toInt() and 0xff, i32(at + 16), if (rawIndex == -1) null else string(rawIndex))
                    if (attrs.put(ns to name, value) != null) fail("Повторяющийся атрибут XML: $name")
                }
                val android = "http://schemas.android.com/apk/res/android"
                fun text(name: String, namespace: String = android): String? = attrs[namespace to name]?.let {
                    if (it.type != 3) fail("$name должен быть строкой; ссылки на ресурсы не поддерживаются")
                    string(it.value)
                }
                fun integer(name: String, default: Int? = null): Int? = attrs[android to name]?.let {
                    when (it.type) { 0x10, 0x11, 0x12 -> it.value; 3 -> string(it.value).toIntOrNull() ?: fail("Неподдерживаемое значение $name")
                        else -> fail("Неподдерживаемое значение $name") }
                } ?: default
                fun types(name: String) = text(name).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                when (element) {
                    "manifest" -> {
                        if (metadata != null || stack.isNotEmpty()) fail("Повторный манифест")
                        val packageName = text("package", "") ?: fail("Манифест без имени пакета")
                        if (!Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+").matches(packageName)) fail("Некорректное имя пакета")
                        val version = integer("versionCode") ?: fail("Манифест без versionCode")
                        val major = integer("versionCodeMajor", 0)!!
                        if (major < 0) fail("Некорректный versionCodeMajor")
                        val split = text("split", "")?.takeIf { it.isNotEmpty() }
                        val configFor = text("configForSplit", "")?.takeIf { it.isNotEmpty() }
                        (listOfNotNull(split, configFor)).forEach { if (!Regex("[A-Za-z0-9_.]+") .matches(it)) fail("Некорректный идентификатор части") }
                        // Отображаемая версия может ссылаться на ресурс; без resources.arsc оставляем её неизвестной.
                        val versionName = if (attrs[android to "versionName"]?.type == 1) "" else text("versionName").orEmpty()
                        metadata = ApkMetadata(packageName, (major.toLong() shl 32) or (version.toLong() and 0xffffffffL),
                            versionName, minSdk, minSdk, split, configFor,
                            integer("isFeatureSplit", 0) != 0, requiredSplitTypes = types("requiredSplitTypes") + (if (integer("isSplitRequired", 0) != 0) setOf(LEGACY_ANY_SPLIT) else emptySet()), splitTypes = types("splitTypes"))
                    }
                    "uses-sdk" -> if (stack.lastOrNull() == "manifest") {
                        minSdk = integer("minSdkVersion", 1)!!; targetSdk = integer("targetSdkVersion", minSdk)
                        if (minSdk < 1 || targetSdk!! < 1) fail("Некорректный SDK манифеста")
                    }
                    "uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m" -> if (stack.lastOrNull() == "manifest") {
                        val name = text("name")?.takeIf { it.isNotBlank() }
                            ?: fail("Элемент $element без атрибута android:name")
                        val maxSdk = integer("maxSdkVersion")
                        if (maxSdk != null && maxSdk < 1) fail("Некорректный maxSdkVersion разрешения")
                        permissions += PermissionDeclaration(name, if (element == "uses-permission") 1 else 23, maxSdk)
                    }
                    "uses-split" -> if (stack.lastOrNull() == "manifest") {
                        uses += text("name")?.takeIf { it.isNotBlank() } ?: fail("Элемент uses-split без атрибута name")
                    }
                }
                if (stack.size >= 256) fail("Слишком глубокий манифест")
                stack += element
            } else if (type == 0x103) {
                if (header < 16) fail("Некорректный закрывающий узел XML")
                range(position + header, 8, end)
                val name = strings?.getOrNull(i32(position + header + 4)) ?: fail("Некорректный закрывающий узел")
                if (stack.removeLastOrNull() != name) fail("Несогласованные узлы XML")
            } else if (type !in setOf(0x100, 0x101, 0x104, 0x180)) fail("Неподдерживаемый блок XML: $type")
            position = end
        }
        if (stack.isNotEmpty()) fail("Незавершённый манифест")
        return (metadata ?: fail("Манифест без корневого элемента")).copy(minSdk = minSdk, targetSdk = targetSdk ?: minSdk, usesSplits = uses.distinct(),
            requestedPermissions = permissions.distinct())
    }
    private fun fail(message: String): Nothing = throw PreparationException(message)
}
