// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.core

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Минимальный ограниченный парсер формата protobuf без сгенерированной или встроенной сторонней реализации. */
internal class Proto(private val fields: List<Field>) {
    data class Field(val number: Int, val wire: Int, val integer: Long = 0, val bytes: ByteArray = byteArrayOf())
    fun numbers(): Set<Int> = fields.map { it.number }.toSet()
    fun repeated(number: Int): List<ByteArray> = fields.filter { it.number == number }.map {
        if (it.wire != 2) bad(); it.bytes
    }
    fun bytes(number: Int): ByteArray? {
        val values = repeated(number); if (values.size > 1) bad(); return values.singleOrNull()
    }
    fun message(number: Int): Proto = parse(bytes(number) ?: byteArrayOf())
    fun messages(number: Int): List<Proto> = repeated(number).map { parse(it) }
    fun integer(number: Int, default: Long = 0): Long {
        val values = fields.filter { it.number == number }
        if (values.size > 1 || values.any { it.wire != 0 }) bad()
        return values.singleOrNull()?.integer ?: default
    }
    fun has(number: Int): Boolean = fields.any { it.number == number }
    fun string(number: Int, default: String = ""): String = bytes(number)?.let { utf8(it) } ?: default
    fun strings(number: Int): List<String> = repeated(number).map { utf8(it) }
    fun only(vararg allowed: Int) { if (numbers().any { it !in allowed }) throw PreparationException("Неподдерживаемые условия совместимости в toc.pb") }
    companion object {
        fun parse(bytes: ByteArray): Proto {
            if (bytes.size > MAX_METADATA_BYTES) throw PreparationException("toc.pb превышает 4 МиБ")
            var cursor = 0
            fun varint(): Long {
                var value = 0L
                for (index in 0..9) {
                    if (cursor == bytes.size) bad()
                    val b = bytes[cursor++].toInt() and 255
                    if (index == 9 && b > 1) bad()
                    value = value or ((b and 127).toLong() shl (index * 7))
                    if (b and 128 == 0) return value
                }
                bad()
            }
            val result = mutableListOf<Field>()
            while (cursor < bytes.size) {
                if (result.size >= 65536) bad()
                val tag = varint()
                if (tag <= 0 || tag ushr 3 > 0x1fffffffL) bad()
                val number = (tag ushr 3).toInt(); val wire = (tag and 7).toInt()
                when (wire) {
                    0 -> result += Field(number, wire, varint())
                    2 -> {
                        val length = varint()
                        if (length < 0 || length > bytes.size - cursor) bad()
                        result += Field(number, wire, bytes = bytes.copyOfRange(cursor, cursor + length.toInt()))
                        cursor += length.toInt()
                    }
                    1, 5 -> {
                        val length = if (wire == 1) 8 else 4
                        if (cursor > bytes.size - length) bad()
                        cursor += length; result += Field(number, wire)
                    }
                    else -> bad()
                }
            }
            return Proto(result)
        }
        private fun utf8(bytes: ByteArray): String = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { bad() }
        private fun bad(): Nothing = throw PreparationException("Повреждённый protobuf toc.pb")
    }
}

internal data class Targeting(
    val sdk: Pair<List<Int>, List<Int>>? = null,
    val abi: Pair<List<String>, List<String>>? = null,
    val density: Pair<List<Int>, List<Int>>? = null,
    val language: Pair<List<String>, List<String>>? = null,
    val multiAbi: Pair<List<Set<String>>, List<Set<String>>>? = null
) {
    fun matches(device: DeviceProfile): Boolean {
        sdk?.let { (values, alternatives) ->
            val chosen = (values + alternatives).filter { it <= device.sdk }.maxOrNull()
            if (values.isNotEmpty() && chosen !in values || values.isEmpty() && chosen != null) return false
        }
        abi?.let { (values, alternatives) ->
            val chosen = device.abis.firstOrNull { it in values || it in alternatives }
            if (values.isNotEmpty() && chosen !in values || values.isEmpty() && chosen != null) return false
        }
        multiAbi?.let { (values, alternatives) ->
            if (values.isNotEmpty() || alternatives.isNotEmpty()) {
                if (values.none { combination -> combination.all { it in device.abis } }) return false
                if (alternatives.any { alternative -> alternative.all { it in device.abis } &&
                        values.all { value -> compareMultiAbi(alternative, value) > 0 } }) return false
            }
        }
        density?.let { (values, alternatives) ->
            val chosen = chooseDensity(values + alternatives, device.densityDpi)
            if (values.isNotEmpty() && chosen !in values || values.isEmpty() && chosen != null) return false
        }
        language?.let { (values, alternatives) ->
            val languages = device.locales.map { language(it) }.toSet()
            if (values.isNotEmpty() && values.none { language(it) in languages } ||
                values.isEmpty() && alternatives.isNotEmpty() && alternatives.map { language(it) }.containsAll(languages)) return false
        }
        return true
    }
    fun sdkFloor(): Int = sdk?.first?.maxOrNull() ?: 0
    fun hasAbiConstraint(): Boolean = abi?.let { (values, alternatives) -> values.isNotEmpty() || alternatives.isNotEmpty() } == true ||
        multiAbi?.let { (values, alternatives) -> values.isNotEmpty() || alternatives.isNotEmpty() } == true
}

/** Для Multi-ABI используем порядок архитектур bundletool и лексикографическое сравнение наборов. */
private fun compareMultiAbi(left: Set<String>, right: Set<String>): Int {
    val order = listOf("armeabi", "armeabi-v7a", "arm64-v8a", "x86", "x86_64", "mips", "mips64", "riscv64")
    val a = left.map { order.indexOf(it) }.sortedDescending()
    val b = right.map { order.indexOf(it) }.sortedDescending()
    for (index in 0 until minOf(a.size, b.size)) {
        if (a[index] != b[index]) return a[index].compareTo(b[index])
    }
    return a.size.compareTo(b.size)
}

internal fun language(value: String): String = value.replace('_', '-').substringBefore('-').lowercase(java.util.Locale.ROOT)
internal fun chooseDensity(candidates: List<Int>, requested: Int): Int? {
    val values = candidates.distinct().sorted()
    if (values.isEmpty()) return null
    if (65534 in values) return 65534 // Android anydpi; nodpi (65535) участвует в числовом сравнении.
    var best = values.first()
    for (candidate in values.drop(1)) {
        val high = candidate.toLong(); val low = best.toLong(); val target = requested.toLong()
        if (target >= high || low < target && (2 * low - target) * high <= target * target) best = candidate
    }
    return best
}

internal data class TocApk(val path: String, val targeting: Targeting, val splitId: String?, val master: Boolean, val standalone: Boolean)
internal data class TocModule(val name: String, val dependencies: List<String>, val targeting: Targeting, val installTime: Boolean, val instant: Boolean, val apks: List<TocApk>)
internal data class TocVariant(val targeting: Targeting, val number: Long, val modules: List<TocModule>)
internal data class TocSelection(val apks: Map<String, TocApk>, val modules: Map<String, TocModule>, val warnings: List<String>, val packageName: String?)

/** Поддерживает обычную установку APK по официальным схемам bundletool commands.proto / targeting.proto. */
class BundleTocReader {
    internal fun select(bytes: ByteArray, device: DeviceProfile): TocSelection {
        val root = Proto.parse(bytes)
        val warnings = mutableListOf<String>()
        if (root.has(3) || root.has(6)) warnings += "Пакеты ресурсов не поддерживаются; игровые ресурсы из отдельных срезов не будут установлены."
        if (root.repeated(1).size > MAX_PACKAGE_APKS) throw PreparationException("Слишком много вариантов в toc.pb")
        var apkCount = 0
        val variants = root.messages(1).map { variant ->
            val modules = variant.messages(2).map { set ->
                val metadata = set.message(1)
                val name = metadata.string(1)
                if (!Regex("[A-Za-z0-9_]+") .matches(name)) throw PreparationException("Некорректное имя модуля в toc.pb")
                val delivery = metadata.integer(6)
                if (delivery !in 0L..3L) throw PreparationException("Неизвестный тип доставки в toc.pb")
                val moduleType = metadata.integer(7)
                if (moduleType !in 0L..1L || metadata.has(8) || metadata.has(9))
                    throw PreparationException("Модуль изолированного SDK или машинного обучения из toc.pb не поддерживается")
                val installTime = delivery == 1L || delivery == 0L && metadata.integer(2) == 0L
                TocModule(name, metadata.strings(4), moduleTargeting(metadata.message(5)), installTime,
                    metadata.integer(3) != 0L, set.messages(2).map { apk ->
                        if (++apkCount > MAX_PACKAGE_APKS) throw PreparationException("Слишком много APK в toc.pb")
                        val path = safeArchivePath(apk.string(2))
                        if (!path.endsWith(".apk", true)) throw PreparationException("toc.pb ссылается на не-APK: $path")
                        val metadataKinds = (3..9).filter { apk.has(it) }
                        if (metadataKinds.size != 1 || metadataKinds.single() !in 3..4)
                            throw PreparationException("Мгновенные, системные, ресурсные APK и APEX из toc.pb не поддерживаются")
                        val standalone = metadataKinds.single() == 4
                        val split = apk.message(if (standalone) 4 else 3)
                        TocApk(path, targeting(apk.message(1), variant = false),
                            if (standalone) split.string(3).takeIf { it.isNotEmpty() } else split.string(1).takeIf { it.isNotEmpty() },
                            standalone || split.integer(2) != 0L, standalone)
                    })
            }
            if (modules.map { it.name }.distinct().size != modules.size) throw PreparationException("Повторяющийся модуль в toc.pb")
            TocVariant(targeting(variant.message(1), variant = true), variant.integer(3), modules)
        }
        val compatible = variants.filter { it.targeting.matches(device) && it.modules.any { module -> module.name == "base" && !module.instant } }
        val variant = compatible.maxWithOrNull(compareBy<TocVariant> { it.number }.thenBy { it.targeting.sdkFloor() })
            ?: throw PreparationException("В APKS нет совместимого варианта для устройства")
        val modules = variant.modules.filter { it.installTime && !it.instant && it.targeting.matches(device) }.associateBy { it.name }
        if ("base" !in modules) throw PreparationException("Выбранный вариант не содержит базовый APK для установки вместе с приложением")
        modules.values.forEach { module ->
            if (module.dependencies.any { it != "base" && it !in modules }) throw PreparationException("Отсутствует зависимость модуля ${module.name} для установки вместе с приложением")
        }
        val paths = linkedMapOf<String, TocApk>()
        modules.values.forEach { module ->
            val apks = module.apks.filter { it.targeting.matches(device) }
            if (module.apks.any { it.targeting.hasAbiConstraint() } && apks.none { it.targeting.hasAbiConstraint() })
                throw PreparationException("Нет APK с совместимыми условиями выбора ABI для модуля ${module.name}")
            if (apks.count { it.master } != 1) throw PreparationException("Модуль ${module.name} должен содержать один совместимый основной APK")
            apks.forEach { if (paths.put(it.path, it) != null) throw PreparationException("Повторяющийся путь APK в toc.pb") }
        }
        if (paths.values.any { it.standalone } && paths.size != 1) throw PreparationException("Нельзя объединять автономный APK с разделёнными частями APK")
        if (variant.modules.any { !it.installTime || it.instant }) warnings += "Загружаемые по требованию и мгновенные модули пропущены; установлены только модули начальной установки."
        return TocSelection(paths, modules, warnings, root.string(4).takeIf { it.isNotEmpty() })
    }
    private fun moduleTargeting(proto: Proto): Targeting {
        proto.only(1)
        return Targeting(sdk = proto.bytes(1)?.let { pair(Proto.parse(it), ::sdk) })
    }
    private fun targeting(proto: Proto, variant: Boolean): Targeting {
        proto.only(*(if (variant) intArrayOf(1, 2, 3, 4) else intArrayOf(1, 3, 4, 5, 7)))
        return if (variant) Targeting(
            sdk = proto.bytes(1)?.let { pair(Proto.parse(it), ::sdk) },
            abi = proto.bytes(2)?.let { pair(Proto.parse(it), ::abi) },
            density = proto.bytes(3)?.let { pair(Proto.parse(it), ::density) },
            multiAbi = proto.bytes(4)?.let { pair(Proto.parse(it), ::multiAbi) }
        ) else Targeting(
            abi = proto.bytes(1)?.let { pair(Proto.parse(it), ::abi) },
            language = proto.bytes(3)?.let { languageTarget(Proto.parse(it)) },
            density = proto.bytes(4)?.let { pair(Proto.parse(it), ::density) },
            sdk = proto.bytes(5)?.let { pair(Proto.parse(it), ::sdk) },
            multiAbi = proto.bytes(7)?.let { pair(Proto.parse(it), ::multiAbi) }
        )
    }
    private fun <T> pair(proto: Proto, convert: (Proto) -> T): Pair<List<T>, List<T>> {
        proto.only(1, 2)
        return proto.messages(1).map(convert) to proto.messages(2).map(convert)
    }
    private fun sdk(proto: Proto): Int {
        proto.only(1); val wrapper = proto.message(1); wrapper.only(1)
        return wrapper.integer(1).takeIf { it in 1L..Int.MAX_VALUE }?.toInt() ?: throw PreparationException("Некорректные условия выбора SDK")
    }
    private fun abi(proto: Proto): String {
        proto.only(1)
        return mapOf(1L to "armeabi", 2L to "armeabi-v7a", 3L to "arm64-v8a", 4L to "x86", 5L to "x86_64", 6L to "mips", 7L to "mips64", 8L to "riscv64")[proto.integer(1)]
            ?: throw PreparationException("Неизвестные условия выбора ABI")
    }
    private fun multiAbi(proto: Proto): Set<String> {
        proto.only(1)
        return proto.messages(1).map { abi(it) }.toSet().takeIf { it.isNotEmpty() } ?: throw PreparationException("Пустые условия выбора нескольких ABI")
    }
    private fun density(proto: Proto): Int {
        proto.only(1, 2)
        if (proto.has(1) == proto.has(2)) throw PreparationException("Некорректные условия выбора плотности экрана")
        return if (proto.has(1)) mapOf(1L to 65535, 2L to 120, 3L to 160, 4L to 213, 5L to 240, 6L to 320, 7L to 480, 8L to 640)[proto.integer(1)]
            ?: throw PreparationException("Неизвестное обозначение плотности экрана")
        else proto.integer(2).takeIf { it in 1L..65535L }?.toInt() ?: throw PreparationException("Некорректная плотность экрана в DPI")
    }
    private fun languageTarget(proto: Proto): Pair<List<String>, List<String>> {
        proto.only(1, 2)
        val values = proto.strings(1); val alternatives = proto.strings(2)
        if ((values + alternatives).any { !Regex("[A-Za-z]{2,3}([_-][A-Za-z0-9]+)*").matches(it) }) throw PreparationException("Неизвестные условия выбора языка")
        return values to alternatives
    }
}
