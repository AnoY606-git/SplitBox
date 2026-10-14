// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.core

import java.util.Locale

internal enum class ConfigKind { ABI, DENSITY, LANGUAGE, GENERIC }
internal data class ConfigTarget(val kind: ConfigKind, val value: String)
internal val knownAbis = setOf("armeabi", "armeabi-v7a", "arm64-v8a", "x86", "x86_64", "mips", "mips64", "riscv64")
internal val densityNames = mapOf("ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240, "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640, "nodpi" to 65535)

internal fun configTarget(apk: ApkEntry): ConfigTarget? {
    val m = apk.metadata
    if (m.splitName == null || m.isFeatureSplit) return null
    val name = m.splitName
    val suffix = when {
        name.startsWith("config.") -> name.removePrefix("config.")
        ".config." in name -> name.substringAfterLast(".config.")
        else -> name
    }
    val abi = suffix.replace("arm64_v8a", "arm64-v8a").replace("armeabi_v7a", "armeabi-v7a")
    if (abi in knownAbis) return ConfigTarget(ConfigKind.ABI, abi)
    if (suffix in densityNames) return ConfigTarget(ConfigKind.DENSITY, densityNames.getValue(suffix).toString())
    if (Regex("[0-9]{2,5}dpi").matches(suffix)) return ConfigTarget(ConfigKind.DENSITY, suffix.removeSuffix("dpi"))
    if (Regex("[a-z]{2,3}([_-]r?[A-Za-z]{2,4})?([_-][A-Za-z0-9]{2,8})?").matches(suffix))
        return ConfigTarget(ConfigKind.LANGUAGE, suffix.replace("_r", "-").replace("-r", "-").replace('_', '-'))
    if (apk.nativeAbis.size == 1) return ConfigTarget(ConfigKind.ABI, apk.nativeAbis.single())
    return ConfigTarget(ConfigKind.GENERIC, suffix)
}

/** Публичная повторная проверка для Android-слоя непосредственно перед созданием сессии. */
fun isCompatible(apk: ApkEntry, device: DeviceProfile): Boolean {
    if (apk.metadata.minSdk > device.sdk || apk.nativeAbis.isNotEmpty() && apk.nativeAbis.none { it in device.abis }) return false
    return when (val target = configTarget(apk)) {
        null -> true
        else -> when (target.kind) {
            ConfigKind.ABI -> target.value in device.abis
            ConfigKind.LANGUAGE -> device.locales.any { localeMatches(target.value, it) }
            ConfigKind.DENSITY -> true
            ConfigKind.GENERIC -> true
        }
    }
}

private fun localeMatches(target: String, locale: String): Boolean {
    val t = target.replace('_', '-').lowercase(Locale.ROOT)
    val d = locale.replace('_', '-').lowercase(Locale.ROOT)
    return if ('-' !in t) language(t) == language(d) else d == t || d.startsWith("$t-")
}
internal fun owner(apk: ApkEntry): String = apk.metadata.configForSplit ?: "base"

/** Проверяем единство метаданных всех кандидатов, включая исключённые конфигурации. */
internal fun validateIdentity(apks: List<ApkEntry>) {
    if (apks.isEmpty()) throw PreparationException("Архив не содержит APK")
    val bases = apks.filter { it.metadata.splitName == null }
    if (bases.size != 1) throw PreparationException("Набор должен содержать ровно один базовый APK")
    val base = bases.single().metadata
    val names = mutableSetOf<String>()
    val paths = mutableSetOf<String>()
    apks.forEach { apk ->
        val m = apk.metadata
        if (m.packageName != base.packageName || m.versionCode != base.versionCode)
            throw PreparationException("APK имеют разные имена пакетов или версии")
        if (!paths.add(apk.archivePath)) throw PreparationException("Дубликат пути APK")
        if (m.splitName != null && !names.add(m.splitName)) throw PreparationException("Повторяющийся идентификатор части: ${m.splitName}")
        if (m.splitName == null && (m.configForSplit != null || m.isFeatureSplit)) throw PreparationException("Некорректный базовый APK")
        if (m.isFeatureSplit && m.configForSplit != null) throw PreparationException("Функциональный модуль не может содержать configForSplit")
    }
    val features = apks.filter { it.metadata.isFeatureSplit }.mapNotNull { it.metadata.splitName }.toSet()
    apks.forEach { apk ->
        apk.metadata.configForSplit?.let { if (it != "base" && it !in features) throw PreparationException("configForSplit ссылается на отсутствующий модуль: $it") }
    }
}

/** Проверяет точный набор выбранных APK перед потоковой передачей в PackageInstaller. */
fun validateSelection(apks: List<ApkEntry>) {
    validateIdentity(apks)
    val selected = apks.filter { it.selected }
    if (selected.count { it.metadata.splitName == null } != 1) throw PreparationException("Базовый APK должен быть выбран")
    apks.filter { it.metadata.isFeatureSplit && it.locked }.forEach {
        if (!it.selected) throw PreparationException("Обязательный модуль должен быть выбран: ${it.metadata.splitName}")
    }
    val modules = selected.filter { it.metadata.splitName == null || it.metadata.isFeatureSplit }
        .associateBy { it.metadata.splitName ?: "base" }
    val selectedNames = selected.mapNotNull { it.metadata.splitName }.toSet()
    selected.forEach { apk ->
        val m = apk.metadata
        if (m.usesSplits.any { it !in selectedNames && it != "base" }) throw PreparationException("Не выбрана зависимость uses-split для ${m.splitName ?: "базового APK"}")
        if (m.configForSplit != null && m.configForSplit !in modules) throw PreparationException("Не выбран модуль для configForSplit: ${m.configForSplit}")
        if (!m.isFeatureSplit && m.splitName != null && owner(apk) !in modules) throw PreparationException("Конфигурационная часть не имеет основного APK")
    }
    val offeredTypes = selected.flatMap { it.metadata.splitTypes }.toSet() +
        (if (selected.any { it.metadata.splitName != null }) setOf(LEGACY_ANY_SPLIT) else emptySet())
    selected.forEach { apk ->
        if (!offeredTypes.containsAll(apk.metadata.requiredSplitTypes))
            throw PreparationException("Не выбраны обязательные типы частей для ${apk.metadata.splitName ?: "базового APK"}: ${(apk.metadata.requiredSplitTypes - offeredTypes).joinToString()}")
    }
    // Сохраняем хотя бы одну конфигурацию ABI каждого доступного модуля, в том числе для старых APK
    // без requiredSplitTypes. Языковые части не могут удовлетворить это требование.
    apks.filter { configTarget(it)?.kind == ConfigKind.ABI }.groupBy { owner(it) }.forEach { (module, entries) ->
        if (module in modules && entries.none { it.selected }) throw PreparationException("Не выбрана совместимая часть ABI для $module")
    }
    // В ручном режиме допустимы несколько конфигураций ABI одного модуля; их ABI объединяются.
    // Разным модулям и основным APK по-прежнему нужна общая архитектура процесса.
    val nativeSets = modules.values.map { it.nativeAbis }.filter { it.isNotEmpty() } +
        selected.filter { it.metadata.splitName != null && !it.metadata.isFeatureSplit && it.nativeAbis.isNotEmpty() }
            .groupBy { owner(it) }.values.map { entries -> entries.flatMap { it.nativeAbis }.toSet() }
    if (nativeSets.isNotEmpty() && nativeSets.reduce { left, right -> left intersect right }.isEmpty())
        throw PreparationException("Выбранные APK содержат несовместимые нативные ABI")
    // Цикл зависимостей модулей недопустим, даже если все указанные модули присутствуют.
    val visiting = mutableSetOf<String>(); val visited = mutableSetOf<String>()
    fun visit(name: String) {
        if (name in visited) return
        if (!visiting.add(name)) throw PreparationException("Циклические зависимости uses-split")
        modules[name]?.metadata?.usesSplits?.filter { it in modules }?.forEach { visit(it) }
        visiting -= name; visited += name
    }
    modules.keys.forEach { visit(it) }
}

class SplitSelector {
    internal fun select(apks: List<ApkEntry>, device: DeviceProfile, autoSelect: Boolean, fromToc: Boolean = false): List<ApkEntry> {
        validateIdentity(apks)
        val primary = apks.filter { it.metadata.splitName == null || it.metadata.isFeatureSplit }
        if (primary.any { !isCompatible(it, device) }) throw PreparationException("Базовый APK или модуль несовместим с SDK или ABI устройства")
        val targets = apks.associateWith { configTarget(it) }
        if (!fromToc && targets.values.any { it?.kind == ConfigKind.GENERIC })
            throw PreparationException("Неизвестные условия совместимости конфигурационной части; требуется bundletool toc.pb")
        val bestAbiByOwner = apks.filter { targets[it]?.kind == ConfigKind.ABI && isCompatible(it, device) }
            .groupBy { owner(it) }.mapValues { (_, entries) -> device.abis.firstOrNull { abi -> entries.any { targets[it]?.value == abi } } }
        val bestDensityByOwner = apks.filter { targets[it]?.kind == ConfigKind.DENSITY && isCompatible(it, device) }
            .groupBy { owner(it) }.mapValues { (_, entries) -> chooseDensity(entries.map { targets.getValue(it)!!.value.toInt() }, device.densityDpi) }
        val result = apks.map { apk ->
            val target = targets[apk]
            val mandatory = apk.metadata.splitName == null || apk.metadata.isFeatureSplit
            val compatible = if (fromToc && target?.kind == ConfigKind.GENERIC) apk.metadata.minSdk <= device.sdk &&
                (apk.nativeAbis.isEmpty() || apk.nativeAbis.any { it in device.abis }) else isCompatible(apk, device)
            val chosen = compatible && (mandatory || fromToc || !autoSelect || when (target?.kind) {
                ConfigKind.ABI -> bestAbiByOwner[owner(apk)] == target.value
                ConfigKind.DENSITY -> bestDensityByOwner[owner(apk)] == target.value.toInt()
                else -> true
            })
            apk.copy(selected = chosen, locked = mandatory || !compatible, selectionReason = when {
                mandatory -> "Обязательный базовый APK или модуль"
                !compatible -> "Несовместим с SDK, ABI или языком устройства"
                chosen -> if (fromToc) "Совместимая конфигурация выбранного варианта APKS" else "Совместимая конфигурация"
                else -> "Автовыбор предпочёл другую конфигурацию"
            })
        }
        // Для набора нативных конфигураций нужна совместимая ABI даже без requiredSplitTypes в старом манифесте.
        apks.filter { targets[it]?.kind == ConfigKind.ABI }.groupBy { owner(it) }.forEach { (module, entries) ->
            if (entries.none { candidate -> result.any { it.archivePath == candidate.archivePath && it.selected } })
                throw PreparationException("Нет совместимой части ABI для $module")
        }
        validateSelection(result)
        return result
    }
}
