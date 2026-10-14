// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import dev.splitbox.core.StagedApk
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException

/** Один проход по каждому источнику; каталог принадлежит только этой операции. */
object MultiApkStager {
    fun <T> stage(sources: List<T>, workDir: File, maxBytes: Long, name: (T) -> String,
        open: (T) -> InputStream, cancelled: () -> Boolean, progress: (Long) -> Unit): List<StagedApk> {
        require(sources.size in 1..128) { "Выберите от 1 до 128 APK одного приложения" }
        require(maxBytes > 0) { "Лимит размера должен быть положительным" }
        require(workDir.isDirectory && !Files.isSymbolicLink(workDir.toPath()) && workDir.listFiles()?.isEmpty() == true) {
            "Нужен пустой закрытый рабочий каталог"
        }
        var total = 0L
        try {
            return sources.mapIndexed { index, source ->
                if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Копирование отменено")
                val display = name(source).take(80).replace(Regex("[^A-Za-zА-Яа-яЁё0-9._-]"), "_")
                    .replace("..", "_").trim('.', '_').ifEmpty { "APK" }
                val prefix = index.toString().padStart(4, '0')
                val destination = File(workDir, "$prefix.apk")
                check(destination.createNewFile()) { "Временный файл уже существует" }
                val copied = open(source).use { input -> destination.outputStream().use { output ->
                    val result = SourceCopier.copy(input, output, maxBytes - total, false, cancelled) { progress(total + it) }
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
