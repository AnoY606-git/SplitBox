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
    if (apks.isEmpty()) throw PreparationException(message("split_selector_archive_contains_no_apks", "Архив не содержит APK"))
    val bases = apks.filter { it.metadata.splitName == null }
    if (bases.size != 1) throw PreparationException(message("split_selector_the_set_must_contain_exactly_one_base", "Набор должен содержать ровно один базовый APK"))
    val base = bases.single().metadata
    val names = mutableSetOf<String>()
    val paths = mutableSetOf<String>()
    apks.forEach { apk ->
        val m = apk.metadata
        if (m.packageName != base.packageName || m.versionCode != base.versionCode)
            throw PreparationException(message("split_selector_apks_have_different_package_names_or_versions", "APK имеют разные имена пакетов или версии"))
        if (!paths.add(apk.archivePath)) throw PreparationException(message("split_selector_duplicate_apk_path", "Дубликат пути APK"))
        if (m.splitName != null && !names.add(m.splitName)) throw PreparationException(message("split_selector_duplicate_split_identifier_1", "Повторяющийся идентификатор части: %1\$s", m.splitName))
        if (m.splitName == null && (m.configForSplit != null || m.isFeatureSplit)) throw PreparationException(message("split_selector_invalid_base_apk", "Некорректный базовый APK"))
        if (m.isFeatureSplit && m.configForSplit != null) throw PreparationException(message("split_selector_a_feature_module_cannot_contain_configforsplit", "Функциональный модуль не может содержать configForSplit"))
    }
    val features = apks.filter { it.metadata.isFeatureSplit }.mapNotNull { it.metadata.splitName }.toSet()
    apks.forEach { apk ->
        apk.metadata.configForSplit?.let { if (it != "base" && it !in features) throw PreparationException(message("split_selector_configforsplit_references_a_missing_module_1", "configForSplit ссылается на отсутствующий модуль: %1\$s", it)) }
    }
}

/** Проверяет точный набор выбранных APK перед потоковой передачей в PackageInstaller. */
fun validateSelection(apks: List<ApkEntry>) {
    validateIdentity(apks)
    val selected = apks.filter { it.selected }
    if (selected.count { it.metadata.splitName == null } != 1) throw PreparationException(message("split_selector_the_base_apk_must_be_selected", "Базовый APK должен быть выбран"))
    apks.filter { it.metadata.isFeatureSplit && it.locked }.forEach {
        if (!it.selected) throw PreparationException(message("split_selector_required_module_must_be_selected_1", "Обязательный модуль должен быть выбран: %1\$s", it.metadata.splitName))
    }
    val modules = selected.filter { it.metadata.splitName == null || it.metadata.isFeatureSplit }
        .associateBy { it.metadata.splitName ?: "base" }
    val selectedNames = selected.mapNotNull { it.metadata.splitName }.toSet()
    selected.forEach { apk ->
        val m = apk.metadata
        if (m.usesSplits.any { it !in selectedNames && it != "base" }) throw PreparationException(message("split_selector_uses_split_dependency_not_selected_for_1", "Не выбрана зависимость uses-split для %1\$s", nestedMessage(m.splitName ?: message("split_selector_base_apk", "базового APK"))))
        if (m.configForSplit != null && m.configForSplit !in modules) throw PreparationException(message("split_selector_module_not_selected_for_configforsplit_1", "Не выбран модуль для configForSplit: %1\$s", m.configForSplit))
        if (!m.isFeatureSplit && m.splitName != null && owner(apk) !in modules) throw PreparationException(message("split_selector_a_configuration_split_has_no_primary_apk", "Конфигурационная часть не имеет основного APK"))
    }
    val offeredTypes = selected.flatMap { it.metadata.splitTypes }.toSet() +
        (if (selected.any { it.metadata.splitName != null }) setOf(LEGACY_ANY_SPLIT) else emptySet())
    selected.forEach { apk ->
        if (!offeredTypes.containsAll(apk.metadata.requiredSplitTypes))
            throw PreparationException(message("split_selector_required_split_types_not_selected_for_1", "Не выбраны обязательные типы частей для %1\$s: %2\$s", nestedMessage(apk.metadata.splitName ?: message("split_selector_base_apk", "базового APK")), (apk.metadata.requiredSplitTypes - offeredTypes).joinToString()))
    }
    // Сохраняем хотя бы одну конфигурацию ABI каждого доступного модуля, в том числе для старых APK
    // без requiredSplitTypes. Языковые части не могут удовлетворить это требование.
    apks.filter { configTarget(it)?.kind == ConfigKind.ABI }.groupBy { owner(it) }.forEach { (module, entries) ->
        if (module in modules && entries.none { it.selected }) throw PreparationException(message("split_selector_no_compatible_abi_split_selected_for_1", "Не выбрана совместимая часть ABI для %1\$s", module))
    }
    // В ручном режиме допустимы несколько конфигураций ABI одного модуля; их ABI объединяются.
    // Разным модулям и основным APK по-прежнему нужна общая архитектура процесса.
    val nativeSets = modules.values.map { it.nativeAbis }.filter { it.isNotEmpty() } +
        selected.filter { it.metadata.splitName != null && !it.metadata.isFeatureSplit && it.nativeAbis.isNotEmpty() }
            .groupBy { owner(it) }.values.map { entries -> entries.flatMap { it.nativeAbis }.toSet() }
    if (nativeSets.isNotEmpty() && nativeSets.reduce { left, right -> left intersect right }.isEmpty())
        throw PreparationException(message("split_selector_selected_apks_contain_incompatible_native_abis", "Выбранные APK содержат несовместимые нативные ABI"))
    // Цикл зависимостей модулей недопустим, даже если все указанные модули присутствуют.
    val visiting = mutableSetOf<String>(); val visited = mutableSetOf<String>()
    fun visit(name: String) {
        if (name in visited) return
        if (!visiting.add(name)) throw PreparationException(message("split_selector_cyclic_uses_split_dependencies", "Циклические зависимости uses-split"))
        modules[name]?.metadata?.usesSplits?.filter { it in modules }?.forEach { visit(it) }
        visiting -= name; visited += name
    }
    modules.keys.forEach { visit(it) }
}

class SplitSelector {
    internal fun select(apks: List<ApkEntry>, device: DeviceProfile, autoSelect: Boolean, fromToc: Boolean = false): List<ApkEntry> {
        validateIdentity(apks)
        val primary = apks.filter { it.metadata.splitName == null || it.metadata.isFeatureSplit }
        if (primary.any { !isCompatible(it, device) }) throw PreparationException(message("split_selector_the_base_apk_or_module_is_incompatible", "Базовый APK или модуль несовместим с SDK или ABI устройства"))
        val targets = apks.associateWith { configTarget(it) }
        if (!fromToc && targets.values.any { it?.kind == ConfigKind.GENERIC })
            throw PreparationException(message("split_selector_unknown_targeting_for_a_configuration_split_bundletool", "Неизвестные условия совместимости конфигурационной части; требуется bundletool toc.pb"))
        // Все модули работают в одном процессе: выбираем общую ABI с учётом библиотек основных APK.
        // Ручной режим и готовый вариант toc сохраняют собственный набор конфигураций.
        val bestAbi = if (autoSelect && !fromToc) {
            val abiSets = apks.filter { targets[it]?.kind == ConfigKind.ABI }.groupBy { owner(it) }.values.map { entries ->
                entries.filter { isCompatible(it, device) }.map { targets.getValue(it)!!.value }.toSet()
            } + primary.map { it.nativeAbis }.filter { it.isNotEmpty() }
            device.abis.firstOrNull { abi -> abiSets.all { abi in it } }.also {
                if (it == null && abiSets.isNotEmpty()) throw PreparationException(message("split_selector_no_common_compatible_abi_for_all_modules", "Нет общей совместимой ABI для всех модулей"))
            }
        } else null
        val bestDensityByOwner = apks.filter { targets[it]?.kind == ConfigKind.DENSITY && isCompatible(it, device) }
            .groupBy { owner(it) }.mapValues { (_, entries) -> chooseDensity(entries.map { targets.getValue(it)!!.value.toInt() }, device.densityDpi) }
        val result = apks.map { apk ->
            val target = targets[apk]
            val mandatory = apk.metadata.splitName == null || apk.metadata.isFeatureSplit
            val compatible = if (fromToc && target?.kind == ConfigKind.GENERIC) apk.metadata.minSdk <= device.sdk &&
                (apk.nativeAbis.isEmpty() || apk.nativeAbis.any { it in device.abis }) else isCompatible(apk, device)
            val chosen = compatible && (mandatory || fromToc || !autoSelect || when (target?.kind) {
                ConfigKind.ABI -> bestAbi == target.value
                ConfigKind.DENSITY -> bestDensityByOwner[owner(apk)] == target.value.toInt()
                else -> true
            })
            apk.copy(selected = chosen, locked = mandatory || !compatible, selectionReason = when {
                mandatory -> message("split_selector_required_base_apk_or_module", "Обязательный базовый APK или модуль")
                !compatible -> message("split_selector_incompatible_with_the_device_sdk_abi_or", "Несовместим с SDK, ABI или языком устройства")
                chosen -> if (fromToc) message("split_selector_compatible_configuration_of_the_selected_apks_variant", "Совместимая конфигурация выбранного варианта APKS") else message("split_selector_compatible_configuration", "Совместимая конфигурация")
                else -> message("split_selector_automatic_selection_preferred_another_configuration", "Автовыбор предпочёл другую конфигурацию")
            })
        }
        // Для набора нативных конфигураций нужна совместимая ABI даже без requiredSplitTypes в старом манифесте.
        apks.filter { targets[it]?.kind == ConfigKind.ABI }.groupBy { owner(it) }.forEach { (module, entries) ->
            if (entries.none { candidate -> result.any { it.archivePath == candidate.archivePath && it.selected } })
                throw PreparationException(message("split_selector_no_compatible_abi_split_for_1", "Нет совместимой части ABI для %1\$s", module))
        }
        validateSelection(result)
        return result
    }
}
