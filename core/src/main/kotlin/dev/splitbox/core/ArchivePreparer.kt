// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.core

import java.io.File
import java.io.IOException
import java.nio.file.Files

class ArchivePreparer {
    fun prepare(source: File, sourceName: String, workDir: File, device: DeviceProfile,
        options: PrepareOptions = PrepareOptions(), onProgress: (String, Float?) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }): PreparedPackage {
        if (!source.isFile) throw PreparationException("Исходный файл не найден")
        if (!workDir.isDirectory || Files.isSymbolicLink(workDir.toPath())) throw PreparationException("Некорректный рабочий каталог")
        if (device.sdk < 1 || device.densityDpi < 1 || device.abis.isEmpty()) throw PreparationException("Некорректный профиль устройства")
        val outputDir = File(workDir, "prepared")
        if (outputDir.exists()) throw PreparationException("Рабочий каталог уже содержит подготовленный пакет")
        // Источник может быть workDir/source.bin, но не должен находиться внутри выходного каталога.
        if (source.canonicalPath == outputDir.canonicalPath || source.canonicalPath.startsWith(outputDir.canonicalPath + File.separator))
            throw PreparationException("Исходный файл находится внутри выходного рабочего каталога")
        val extension = sourceName.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        if (extension in setOf("aab", "apex")) throw PreparationException("Формат .$extension нельзя установить как APK")
        if (!outputDir.mkdir()) throw PreparationException("Не удалось создать выходной рабочий каталог")
        try {
            checkCancelled(isCancelled)
            val budget = ByteBudget(options.maxExpandedBytes)
            onProgress("Анализ архива", null)
            val files: Map<String, File> = if (extension == "apk" || isRawApk(source)) {
                // source.bin уже скопирован в закрытый workDir. Сохраняем его для восстановления,
                // используя тот же файл в установке вместо второй полной копии APK.
                val ownedSource = source.name == "source.bin" && !Files.isSymbolicLink(source.toPath()) &&
                    source.canonicalFile.parentFile == workDir.canonicalFile
                val destination = if (ownedSource) {
                    if (source.length() > options.maxExpandedBytes) throw PreparationException("Превышен лимит распаковки (${options.maxExpandedBytes} байт)")
                    checkCancelled(isCancelled)
                    source
                } else File(outputDir, "base.apk").also { destination ->
                    source.inputStream().use { input -> destination.outputStream().use { out ->
                        copyBounded(input, out, budget, isCancelled, outputDir) { count ->
                            onProgress("Копирование APK", if (source.length() > 0) count.toFloat() / source.length() else null)
                        }
                    } }
                }
                mapOf("base.apk" to destination)
            } else SafeArchiveExtractor().extract(source, outputDir, budget, isCancelled, onProgress)
            val tocFiles = files.filterKeys { it.substringAfterLast('/') == "toc.pb" }
            if (tocFiles.size > 1 || tocFiles.isNotEmpty() && "toc.pb" !in tocFiles) throw PreparationException("toc.pb должен быть единственным в корне APKS")
            val toc = files["toc.pb"]?.let { file ->
                checkCancelled(isCancelled)
                BundleTocReader().select(file.inputStream().use { readMetadata(it, isCancelled) }, device)
            }
            val candidates = if (toc == null) files.filterKeys { it.endsWith(".apk", true) } else {
                toc.apks.keys.associateWith { path -> files[path] ?: throw PreparationException("APK из toc.pb отсутствует: $path") }
            }
            val entries = candidates.entries.mapIndexed { index, (path, file) ->
                checkCancelled(isCancelled)
                onProgress("Чтение APK: ${index + 1}/${candidates.size}", (index + 1).toFloat() / candidates.size.coerceAtLeast(1))
                ApkInspector().inspect(file, path, isCancelled)
            }
            validateIdentity(entries)
            if (toc != null) verifyTocMetadata(toc, entries)
            val selected = SplitSelector().select(entries, device, options.autoSelect, fromToc = toc != null)
            val base = selected.single { it.metadata.splitName == null }.metadata
            val obb = files.filterKeys { it.endsWith(".obb", true) }.map { (path, file) ->
                val match = Regex("(main|patch)\\.([0-9]+)\\.([A-Za-z0-9_.]+)\\.obb").matchEntire(file.name)
                    ?: throw PreparationException("Некорректное имя OBB: ${file.name}")
                if (match.groupValues[2].toLongOrNull() == null || match.groupValues[3] != base.packageName)
                    throw PreparationException("OBB имеет некорректную версию расширения или другое имя пакета")
                ObbEntry(file, path, file.length())
            }
            if (obb.map { it.file.name }.distinct().size != obb.size) throw PreparationException("Повторяющееся имя файла OBB")
            val warnings = toc?.warnings.orEmpty().toMutableList()
            if (obb.isNotEmpty()) warnings += "OBB найден; обычная установка APK не переносит OBB. Используйте экспорт или включите перенос с Root/Shizuku."
            if (toc == null && files.keys.any { it.substringAfterLast('/').equals("manifest.json", true) })
                warnings += "Метаданные XAPK/APKM являются подсказкой; пакет и версия проверены по AndroidManifest.xml каждого APK."
            checkCancelled(isCancelled)
            onProgress("Пакет подготовлен", 1f)
            return PreparedPackage(sourceName, base, selected, obb, warnings, workDir)
        } catch (e: Exception) {
            outputDir.deleteRecursively()
            when (e) {
                is PreparationException -> throw e
                is IOException -> throw PreparationException("Не удалось прочитать APK/ZIP: ${e.message.orEmpty().take(200)}")
                else -> throw PreparationException("Не удалось подготовить пакет: ${e.message.orEmpty().take(200)}")
            }
        }
    }
    private fun isRawApk(source: File): Boolean = openCheckedZip(source).use { zip -> zip.getEntry("AndroidManifest.xml") != null }
    private fun verifyTocMetadata(toc: TocSelection, entries: List<ApkEntry>) {
        val base = entries.single { it.metadata.splitName == null }.metadata
        if (toc.packageName != null && toc.packageName != base.packageName) throw PreparationException("Имя пакета в toc.pb не совпадает с APK")
        entries.forEach { apk ->
            val description = toc.apks.getValue(apk.archivePath)
            if (!description.standalone && description.splitId != apk.metadata.splitName)
                throw PreparationException("Идентификатор части в toc.pb не совпадает с манифестом: ${apk.archivePath}")
        }
        toc.modules.values.forEach { module ->
            val descriptions = module.apks.filter { it.path in toc.apks }
            val master = descriptions.single { it.master }
            val entry = entries.single { it.archivePath == master.path }
            if (module.name == "base" && entry.metadata.splitName != null || module.name != "base" &&
                (!entry.metadata.isFeatureSplit || entry.metadata.splitName != module.name))
                throw PreparationException("Основной APK модуля в toc.pb не совпадает с манифестом: ${module.name}")
            val manifestDeps = entry.metadata.usesSplits.toSet()
            if (!manifestDeps.containsAll(module.dependencies.filter { it != "base" }))
                throw PreparationException("Зависимости в toc.pb не совпадают с uses-split: ${module.name}")
            descriptions.filter { !it.master }.forEach { config ->
                val metadata = entries.single { it.archivePath == config.path }.metadata
                if (metadata.isFeatureSplit || (metadata.configForSplit ?: "base") != module.name)
                    throw PreparationException("configForSplit в toc.pb не совпадает с манифестом")
            }
        }
    }
}
