// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException

data class StagedApk(val file: File, val archivePath: String)

/** Проверяет уже скопированные APK без распаковки или повторного копирования. */
class StagedApkPreparer {
    fun prepare(files: List<StagedApk>, workDir: File, device: DeviceProfile,
        options: PrepareOptions = PrepareOptions(), onProgress: (String, Float?) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }): PreparedPackage {
        if (!workDir.isDirectory || Files.isSymbolicLink(workDir.toPath())) throw PreparationException("Некорректный рабочий каталог")
        if (files.size !in 1..128) throw PreparationException("Выберите от 1 до 128 APK одного приложения")
        if (device.sdk < 1 || device.densityDpi < 1 || device.abis.isEmpty()) throw PreparationException("Некорректный профиль устройства")
        if (options.maxExpandedBytes <= 0) throw PreparationException("Лимит размера должен быть положительным")
        // Псевдоним в предке workDir допустим; ссылки внутри проверяются до канонизации APK.
        val root = workDir.absoluteFile.toPath()
        val canonicalRoot = workDir.canonicalFile.toPath()
        val seen = mutableSetOf<java.nio.file.Path>()
        var total = 0L
        try {
            val entries = files.mapIndexed { index, staged ->
                ensureActive(isCancelled)
                val path = staged.file.toPath().toAbsolutePath()
                // Не скрываем link/../file нормализацией до проверки компонентов пути.
                if (path != path.normalize()) throw PreparationException("Некорректный путь временного APK")
                if (!path.startsWith(root) || path == root) throw PreparationException("APK находится вне рабочего каталога")
                var current = path
                while (current != root) {
                    if (Files.isSymbolicLink(current)) throw PreparationException("Символические ссылки APK запрещены")
                    current = current.parent
                }
                if (!staged.file.isFile || !staged.file.canonicalFile.toPath().startsWith(canonicalRoot))
                    throw PreparationException("Временный APK недоступен")
                if (seen.any { Files.isSameFile(it, path) } || !seen.add(staged.file.canonicalFile.toPath()))
                    throw PreparationException("Повторяющийся файл APK")
                val size = staged.file.length()
                if (size <= 0 || size > options.maxExpandedBytes - total) throw PreparationException("Превышен общий лимит размера APK или выбран пустой файл")
                total += size
                onProgress("Чтение APK: ${index + 1}/${files.size}", (index + 1).toFloat() / files.size)
                ApkInspector().inspect(staged.file, staged.archivePath) { ensureActive(isCancelled); false }
            }
            validateIdentity(entries)
            val selected = SplitSelector().select(entries, device, options.autoSelect)
            ensureActive(isCancelled)
            onProgress("Пакет подготовлен", 1f)
            return PreparedPackage("Набор из ${files.size} APK", selected.single { it.metadata.splitName == null }.metadata,
                selected, emptyList(), emptyList(), workDir)
        } catch (e: CancellationException) { throw e }
        catch (e: PreparationException) { throw e }
        catch (e: IOException) { throw PreparationException("Не удалось прочитать APK: ${e.message.orEmpty().take(200)}") }
    }
    private fun ensureActive(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Подготовка отменена")
    }
}
