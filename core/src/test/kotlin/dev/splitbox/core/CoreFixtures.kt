// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Небольшие тестовые образцы проверяют реальные байтовые форматы AXML/protobuf/ZIP. */
internal object CoreFixtures {
    data class Permission(val name: String, val tag: String = "uses-permission", val maxSdk: Int? = null,
        val namespace: String = "http://schemas.android.com/apk/res/android", val type: Int = 3)
    private fun ints(vararg values: Int): ByteArray = ByteBuffer.allocate(values.size * 4)
        .order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putInt(it) } }.array()
    private fun short(value: Int): ByteArray = byteArrayOf(value.toByte(), (value ushr 8).toByte())
    private fun chunk(type: Int, headerSize: Int, body: ByteArray): ByteArray =
        short(type) + short(headerSize) + ints(body.size + 8) + body
    fun manifest(packageName: String = "dev.test", version: Int = 7, split: String? = null,
        configFor: String? = null, feature: Boolean = false, minSdk: Int = 23,
        uses: List<String> = emptyList(), requiredTypes: String? = null, splitTypes: String? = null, versionMajor: Int = 0, legacyRequired: Boolean = false,
        permissions: List<Permission> = emptyList()): ByteArray {
        val strings = mutableListOf<String>()
        fun index(s: String): Int = strings.indexOf(s).takeIf { it >= 0 } ?: strings.size.also { strings += s }
        val android = index("http://schemas.android.com/apk/res/android")
        data class Attr(val ns: Int, val name: Int, val value: Int, val type: Int)
        fun text(name: String, value: String, ns: Int = -1) = Attr(ns, index(name), index(value), 3)
        fun number(name: String, value: Int) = Attr(android, index(name), value, 0x10)
        fun element(name: String, attrs: List<Attr>): ByteArray {
            val nameIndex = index(name)
            val body = ints(1, -1, -1, nameIndex) + short(20) + short(20) + short(attrs.size) +
                short(0) + short(0) + short(0) + attrs.fold(byteArrayOf()) { a, attr ->
                a + ints(attr.ns, attr.name, if (attr.type == 3) attr.value else -1) +
                    short(8) + byteArrayOf(0, attr.type.toByte()) + ints(attr.value)
            }
            return chunk(0x102, 16, body)
        }
        val root = mutableListOf(text("package", packageName), number("versionCode", version),
            text("versionName", "1.7", android))
        if (versionMajor != 0) root += number("versionCodeMajor", versionMajor)
        if (legacyRequired) root += Attr(android, index("isSplitRequired"), 1, 0x12)
        split?.let { root += text("split", it) }
        configFor?.let { root += text("configForSplit", it) }
        if (feature) root += Attr(android, index("isFeatureSplit"), 1, 0x12)
        requiredTypes?.let { root += text("requiredSplitTypes", it, android) }
        splitTypes?.let { root += text("splitTypes", it, android) }
        fun end(name: String) = chunk(0x103, 16, ints(1, -1, -1, index(name)))
        val nodes = element("manifest", root) + element("uses-sdk", listOf(number("minSdkVersion", minSdk), number("targetSdkVersion", 36))) + end("uses-sdk") +
            uses.fold(byteArrayOf()) { b, dep -> b + element("uses-split", listOf(text("name", dep, android))) + end("uses-split") } + permissions.fold(byteArrayOf()) { b, p ->
                val attrs = mutableListOf(Attr(index(p.namespace), index("name"), index(p.name), p.type))
                p.maxSdk?.let { attrs += number("maxSdkVersion", it) }
                b + element(p.tag, attrs) + end(p.tag)
            } + end("manifest")
        val encoded = strings.map { it.toByteArray(Charsets.UTF_8).let { b -> byteArrayOf(it.length.toByte(), b.size.toByte()) + b + byteArrayOf(0) } }
        var offset = 0
        val offsets = encoded.fold(byteArrayOf()) { b, s -> (b + ints(offset)).also { offset += s.size } }
        val poolBody = ints(strings.size, 0, 0x100, 28 + offsets.size, 0) + offsets + encoded.fold(byteArrayOf()) { b, s -> b + s }
        val pool = chunk(1, 28, poolBody)
        return chunk(3, 8, pool + nodes)
    }
    fun zip(file: File, entries: List<Pair<String, ByteArray>>): File {
        ZipOutputStream(file.outputStream()).use { out -> entries.forEach { (name, bytes) ->
            out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry()
        } }
        return file
    }
    fun apk(file: File, manifest: ByteArray = manifest(), abi: String? = null): File = zip(file,
        listOf("AndroidManifest.xml" to manifest) + (abi?.let { listOf("lib/$it/libfixture.so" to byteArrayOf(1, 2, 3)) } ?: emptyList()))
    fun bytesApk(manifest: ByteArray = manifest(), abi: String? = null): ByteArray {
        val temp = File.createTempFile("fixture", ".apk")
        return try { apk(temp, manifest, abi).readBytes() } finally { temp.delete() }
    }
    fun pb(vararg fields: Pair<Int, ByteArray>): ByteArray = fields.fold(byteArrayOf()) { out, (tag, bytes) -> out + varint((tag.toLong() shl 3) or 2) + varint(bytes.size.toLong()) + bytes }
    fun scalar(field: Int, value: Long): ByteArray = varint(field.toLong() shl 3) + varint(value)
    fun varint(value: Long): ByteArray {
        var v = value; val out = ByteArrayOutputStream()
        do { var b = (v and 127).toInt(); v = v ushr 7; if (v != 0L) b = b or 128; out.write(b) } while (v != 0L)
        return out.toByteArray()
    }
    fun text(value: String) = value.toByteArray(Charsets.UTF_8)
    fun sdk(min: Int) = pb(1 to pb(1 to scalar(1, min.toLong())))
    fun abi(alias: Int) = pb(1 to scalar(1, alias.toLong()))
    fun apkTargetAbi(alias: Int) = pb(1 to abi(alias))
    fun variantTargetSdk(min: Int) = pb(1 to sdk(min))
    fun variant(target: ByteArray, vararg sets: ByteArray) = pb(1 to target, *sets.map { 2 to it }.toTypedArray())
    fun apkDescription(path: String, targeting: ByteArray = byteArrayOf(), master: Boolean = false,
        splitId: String = if (master) "" else path.substringAfterLast('/').substringBeforeLast('.')): ByteArray =
        pb(1 to targeting, 2 to text(path), 3 to (pb(1 to text(splitId)) + scalar(2, if (master) 1 else 0)))
    fun apkSet(module: String, vararg apks: ByteArray, delivery: Int = 1, dependencies: List<String> = emptyList()): ByteArray =
        pb(1 to (pb(1 to text(module), *dependencies.map { 4 to text(it) }.toTypedArray()) + scalar(6, delivery.toLong())),
            *apks.map { 2 to it }.toTypedArray())
    fun toc(vararg variants: ByteArray) = pb(*variants.map { 1 to it }.toTypedArray())
}
