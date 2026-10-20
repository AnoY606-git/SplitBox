// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.Locale

/** Явное вложенное сообщение. Обычные строки аргументов всегда остаются внешними данными. */
class NestedMessage internal constructor(internal val value: String)
fun nestedMessage(value: String?): NestedMessage = NestedMessage(value.orEmpty())

/** Совместимый контейнер только для полей отображаемого текста; не используется в протоколах. */
fun message(key: String, diagnosticTemplate: String, vararg arguments: Any?): String =
    MessageEnvelope.encode(key, diagnosticTemplate, arguments.toList())

object MessageEnvelope {
    private const val PREFIX = "\u001eSPLITBOX_MESSAGE/1|"
    private const val MAX_FIELD = 8_192
    private const val MAX_MESSAGE = 8_192
    private const val MAX_DEPTH = 8
    private const val MAX_ARGUMENTS = 32
    private val validKey = Regex("[a-z][a-z0-9_]{0,79}")
    private data class Part(val value: Any?, val nested: Boolean)
    private data class Decoded(val key: String, val arguments: List<Part>, val fallback: String, val end: Int)

    internal fun encode(key: String, template: String, arguments: List<Any?>): String {
        require(validKey.matches(key))
        require(arguments.size <= MAX_ARGUMENTS)
        val parts = arguments.map { value ->
            when (value) {
                is NestedMessage -> Part(compactNested(value.value, MAX_FIELD, 0), true)
                is Number, is Boolean -> Part(value, false)
                null -> Part("", false)
                else -> compactPart(Part(value.toString(), false), MAX_FIELD, 0)
            }
        }
        val fallbackArguments = parts.map { if (it.nested) diagnostic(it.value.toString()) else it.value }.toTypedArray()
        val fallback = runCatching { String.format(Locale.forLanguageTag("ru"), template, *fallbackArguments) }
            .getOrDefault(template).take(MAX_FIELD)
        val original = pack(key, parts, fallback)
        if (wireBytes(original) <= MAX_MESSAGE) return original
        // Бюджет уменьшается конечным числом шагов. Повторного encode с теми же данными нет.
        for (budget in intArrayOf(2048, 1024, 512, 256, 128, 64, 32, 16)) {
            val compact = parts.map { compactPart(it, budget, 0) }
            val candidate = pack(key, compact, utf8Prefix(fallback, budget))
            if (wireBytes(candidate) <= MAX_MESSAGE) return candidate
        }
        return truncated("", 0)
    }

    private fun pack(key: String, parts: List<Part>, fallback: String): String {
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeUTF(key)
                out.writeInt(parts.size)
                parts.forEach { part ->
                    out.writeBoolean(part.nested)
                    when (val value = part.value) {
                        is Float, is Double -> { out.writeByte(1); out.writeDouble((value as Number).toDouble()) }
                        is Number -> { out.writeByte(2); out.writeLong(value.toLong()) }
                        else -> { out.writeByte(0); writeText(out, value.toString()) }
                    }
                }
            }
        }.toByteArray()
        return PREFIX + Base64.getEncoder().encodeToString(bytes) + "|" + fallback.length + "|" + fallback
    }

    private fun wireBytes(value: String): Int = value.toByteArray(Charsets.UTF_8).size

    /** Бюджет считается в UTF-8 байтах; пара UTF-16 surrogate не разрезается. */
    private fun utf8Prefix(value: String, maximum: Int): String {
        var bytes = 0
        var end = 0
        while (end < value.length) {
            val point = value.codePointAt(end)
            val count = when {
                point <= 0x7f -> 1
                point <= 0x7ff -> 2
                point <= 0xffff -> 3
                else -> 4
            }
            if (bytes + count > maximum) break
            bytes += count
            end += Character.charCount(point)
        }
        return value.substring(0, end)
    }

    /** Обрезанный native prefix остаётся обычным аргументом и никогда не декодируется повторно. */
    private fun truncated(value: String, budget: Int): String {
        val prefix = utf8Prefix(value, budget.coerceIn(0, 1024))
        return pack("message_details_truncated", listOf(Part(prefix, false)), "$prefix… [Подробности сокращены]")
    }

    private fun compactPart(part: Part, budget: Int, depth: Int): Part = when {
        part.nested && depth >= MAX_DEPTH -> Part(truncated("", 0), true)
        part.nested -> Part(compactNested(part.value.toString(), budget, depth + 1), true)
        part.value is String && wireBytes(part.value) > budget -> Part(truncated(part.value, budget), true)
        else -> part
    }

    private fun compactNested(text: String, budget: Int, depth: Int): String {
        if (depth >= MAX_DEPTH || budget <= 0) return truncated("", 0)
        val output = StringBuilder()
        val marker = truncated("", 0)
        var used = 0
        fun appendChunk(chunk: String): Boolean {
            val bytes = wireBytes(chunk)
            if (used + bytes > MAX_MESSAGE - wireBytes(marker)) {
                output.append(marker)
                return false
            }
            output.append(chunk)
            used += bytes
            return true
        }
        fun plain(value: String): String = if (wireBytes(value) > budget) truncated(value, budget) else value
        var cursor = 0
        while (cursor < text.length) {
            val start = text.indexOf(PREFIX, cursor)
            if (start < 0) { appendChunk(plain(text.substring(cursor))); break }
            if (!appendChunk(plain(text.substring(cursor, start)))) break
            val decoded = decode(text, start)
            if (decoded == null) { appendChunk(plain(text.substring(start))); break }
            val args = decoded.arguments.map { compactPart(it, budget, depth) }
            if (!appendChunk(pack(decoded.key, args, utf8Prefix(decoded.fallback, minOf(budget, 1024))))) break
            cursor = decoded.end
        }
        return output.toString()
    }

    private fun writeText(out: DataOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readText(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 0..MAX_MESSAGE && length <= input.available())
        return ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
    }

    private fun decode(text: String, start: Int): Decoded? = runCatching {
        val payloadStart = start + PREFIX.length
        val payloadEnd = text.indexOf('|', payloadStart)
        require(payloadEnd in payloadStart..(payloadStart + MAX_MESSAGE).coerceAtMost(text.length))
        val lengthEnd = text.indexOf('|', payloadEnd + 1)
        require(lengthEnd in (payloadEnd + 2)..(payloadEnd + 8).coerceAtMost(text.length))
        val length = text.substring(payloadEnd + 1, lengthEnd).toInt()
        require(length in 0..MAX_FIELD && length <= text.length - lengthEnd - 1)
        val input = DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(text.substring(payloadStart, payloadEnd))))
        val key = input.readUTF()
        require(validKey.matches(key))
        val count = input.readInt()
        require(count in 0..MAX_ARGUMENTS)
        val args = List(count) {
            val nested = input.readBoolean()
            val value: Any = when (input.readByte().toInt()) {
                0 -> readText(input)
                1 -> input.readDouble()
                2 -> input.readLong()
                else -> error("Invalid message argument")
            }
            Part(value, nested)
        }
        require(input.available() == 0)
        Decoded(key, args, text.substring(lengthEnd + 1, lengthEnd + 1 + length), lengthEnd + 1 + length)
    }.getOrNull()

    fun resolve(text: String, renderer: (String, List<Any?>) -> String?): String = resolve(text, renderer, 0)
    private fun resolve(text: String, renderer: (String, List<Any?>) -> String?, depth: Int): String = buildString {
        var cursor = 0
        while (cursor < text.length) {
            val start = text.indexOf(PREFIX, cursor)
            if (start < 0) { append(text.substring(cursor)); break }
            append(text.substring(cursor, start))
            val decoded = decode(text, start)
            if (decoded == null) { append(text.substring(start)); break }
            if (depth >= MAX_DEPTH) append(renderer("message_details_truncated", listOf("")) ?: decoded.fallback)
            else {
                val arguments = decoded.arguments.map { if (it.nested) resolve(it.value.toString(), renderer, depth + 1) else it.value }
                append(renderer(decoded.key, arguments) ?: decoded.fallback)
            }
            cursor = decoded.end
        }
    }
    fun diagnostic(text: String): String = resolve(text) { _, _ -> null }

    /** Меняем только текстовые поля, сохраняя ключи и границы контейнера. */
    fun sanitize(text: String, transform: (String) -> String): String {
        val sanitized = sanitize(text, transform, 0)
        return if (wireBytes(sanitized) <= MAX_MESSAGE) sanitized else compactNested(sanitized, 1024, 0)
    }
    private fun sanitize(text: String, transform: (String) -> String, depth: Int): String = buildString {
        var cursor = 0
        while (cursor < text.length) {
            val start = text.indexOf(PREFIX, cursor)
            if (start < 0) { append(transform(text.substring(cursor))); break }
            append(transform(text.substring(cursor, start)))
            val decoded = decode(text, start)
            if (decoded == null) { append(transform(text.substring(start))); break }
            val args = decoded.arguments.map {
                when {
                    it.nested && depth < MAX_DEPTH -> NestedMessage(sanitize(it.value.toString(), transform, depth + 1))
                    it.nested -> NestedMessage(truncated("", 0))
                    it.value is String -> transform(it.value)
                    else -> it.value
                }
            }
            // Fallback уже отформатирован: удваиваем проценты, чтобы не трактовать внешние данные как format.
            val fallback = transform(decoded.fallback).replace("%", "%%")
            append(encode(decoded.key, fallback, args))
            cursor = decoded.end
        }
    }
    /** Ограничение native diagnostics не должно разрезать сериализованное сообщение. */
    fun limit(text: String?, maximum: Int): String {
        require(maximum >= 0)
        val value = text.orEmpty()
        if (!value.contains(PREFIX)) return if (wireBytes(value) <= minOf(maximum, MAX_MESSAGE)) value else truncated(value, maximum)
        return if (wireBytes(value) <= MAX_MESSAGE) value else compactNested(value, maximum.coerceAtMost(1024), 0)
    }
}
