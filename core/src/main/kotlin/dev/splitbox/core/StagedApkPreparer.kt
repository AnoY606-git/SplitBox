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
        if (!workDir.isDirectory || Files.isSymbolicLink(workDir.toPath())) throw PreparationException(message("archive_preparer_invalid_working_directory", "Некорректный рабочий каталог"))
        if (files.size !in 1..128) throw PreparationException(message("multi_apk_stager_select_1_to_128_apks_for_a", "Выберите от 1 до 128 APK одного приложения"))
        if (device.sdk < 1 || device.densityDpi < 1 || device.abis.isEmpty()) throw PreparationException(message("archive_preparer_invalid_device_profile", "Некорректный профиль устройства"))
        if (options.maxExpandedBytes <= 0) throw PreparationException(message("multi_apk_stager_the_size_limit_must_be_positive", "Лимит размера должен быть положительным"))
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
                if (path != path.normalize()) throw PreparationException(message("staged_apk_preparer_invalid_temporary_apk_path", "Некорректный путь временного APK"))
                if (!path.startsWith(root) || path == root) throw PreparationException(message("staged_apk_preparer_apk_is_outside_the_working_directory", "APK находится вне рабочего каталога"))
                var current = path
                while (current != root) {
                    if (Files.isSymbolicLink(current)) throw PreparationException(message("staged_apk_preparer_apk_symbolic_links_are_not_allowed", "Символические ссылки APK запрещены"))
                    current = current.parent
                }
                if (!staged.file.isFile || !staged.file.canonicalFile.toPath().startsWith(canonicalRoot))
                    throw PreparationException(message("staged_apk_preparer_temporary_apk_is_unavailable", "Временный APK недоступен"))
                if (seen.any { Files.isSameFile(it, path) } || !seen.add(staged.file.canonicalFile.toPath()))
                    throw PreparationException(message("staged_apk_preparer_duplicate_apk_file", "Повторяющийся файл APK"))
                val size = staged.file.length()
                if (size <= 0 || size > options.maxExpandedBytes - total) throw PreparationException(message("staged_apk_preparer_total_apk_size_limit_exceeded_or_an", "Превышен общий лимит размера APK или выбран пустой файл"))
                total += size
                onProgress(message("staged_apk_preparer_reading_apk_1_2", "Чтение APK: %1\$s/%2\$s", index + 1, files.size), (index + 1).toFloat() / files.size)
                ApkInspector().inspect(staged.file, staged.archivePath) { ensureActive(isCancelled); false }
            }
            validateIdentity(entries)
            val selected = SplitSelector().select(entries, device, options.autoSelect)
            ensureActive(isCancelled)
            onProgress(message("archive_preparer_package_prepared", "Пакет подготовлен"), 1f)
            return PreparedPackage(message("staged_apk_preparer_set_of_1_apks", "Набор из %1\$s APK", files.size), selected.single { it.metadata.splitName == null }.metadata,
                selected, emptyList(), emptyList(), workDir)
        } catch (e: CancellationException) { throw e }
        catch (e: PreparationException) { throw e }
        catch (e: IOException) { throw PreparationException(message("staged_apk_preparer_could_not_read_apk_1", "Не удалось прочитать APK: %1\$s", nestedMessage(MessageEnvelope.limit(e.message, 200)))) }
    }
    private fun ensureActive(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException(message("staged_apk_preparer_preparation_canceled", "Подготовка отменена"))
    }
}
