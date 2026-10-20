// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import dev.splitbox.core.message
import dev.splitbox.core.nestedMessage

import dev.splitbox.core.StagedApk
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException

/** Один проход по каждому источнику; каталог принадлежит только этой операции. */
object MultiApkStager {
    fun <T> stage(sources: List<T>, workDir: File, maxBytes: Long, name: (T) -> String,
        open: (T) -> InputStream, cancelled: () -> Boolean, progress: (Long) -> Unit): List<StagedApk> {
        require(sources.size in 1..128) { message("multi_apk_stager_select_1_to_128_apks_for_a", "Выберите от 1 до 128 APK одного приложения") }
        require(maxBytes > 0) { message("multi_apk_stager_the_size_limit_must_be_positive", "Лимит размера должен быть положительным") }
        require(workDir.isDirectory && !Files.isSymbolicLink(workDir.toPath()) && workDir.listFiles()?.isEmpty() == true) {
            message("multi_apk_stager_an_empty_private_working_directory_is_required", "Нужен пустой закрытый рабочий каталог")
        }
        var total = 0L
        try {
            return sources.mapIndexed { index, source ->
                if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException(message("multi_apk_stager_copying_canceled", "Копирование отменено"))
                val display = name(source).take(80).replace(Regex("[^A-Za-zА-Яа-яЁё0-9._-]"), "_")
                    .replace("..", "_").trim('.', '_').ifEmpty { "APK" }
                val prefix = index.toString().padStart(4, '0')
                val destination = File(workDir, "$prefix.apk")
                check(destination.createNewFile()) { message("multi_apk_stager_the_temporary_file_already_exists", "Временный файл уже существует") }
                val copied = open(source).use { input -> destination.outputStream().use { output ->
                    val result = SourceCopier.copy(input, output, maxBytes - total, false, cancelled, { progress(total + it) }) { required ->
                        PackageSizePolicy.requireSpace(required, workDir.usableSpace)
                    }
                    output.fd.sync()
                    result
                } }
                total += copied.bytes
                StagedApk(destination, "$prefix-$display")
            }
        } catch (e: Exception) {
            workDir.deleteRecursively()
            throw e
        }
    }
}
