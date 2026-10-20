// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import dev.splitbox.core.message
import dev.splitbox.core.nestedMessage

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

data class SourceCopyResult(val bytes: Long, val sha256: String?)

/** Один ограниченный проход по источнику; жизненным циклом потоков и fsync управляет вызывающий код. */
object SourceCopier {
    fun copy(input: InputStream, output: OutputStream, maxBytes: Long, computeSha256: Boolean,
        cancelled: () -> Boolean, progress: (Long) -> Unit, checkSpace: (Long) -> Unit = {}): SourceCopyResult {
        require(maxBytes >= 0) { message("source_copier_the_copy_limit_cannot_be_negative", "Лимит копирования не может быть отрицательным") }
        val digest = if (computeSha256) MessageDigest.getInstance("SHA-256") else null
        val buffer = ByteArray(65536)
        var bytes = 0L
        fun checkCancelled() {
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException(message("multi_apk_stager_copying_canceled", "Копирование отменено"))
        }
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            checkCancelled()
            if (count < 0) break
            if (count == 0) continue
            if (count.toLong() > maxBytes - bytes) throw IOException(message("source_copier_the_input_file_exceeds_the_limit_of", "Входной файл превышает лимит %1\$s байт", maxBytes))
            checkSpace(count.toLong())
            output.write(buffer, 0, count)
            digest?.update(buffer, 0, count)
            bytes += count
            progress(bytes)
        }
        if (bytes == 0L) throw IOException(message("source_copier_an_empty_file_was_selected", "Выбран пустой файл"))
        return SourceCopyResult(bytes, digest?.digest()?.joinToString("") { "%02x".format(it) })
    }
}
