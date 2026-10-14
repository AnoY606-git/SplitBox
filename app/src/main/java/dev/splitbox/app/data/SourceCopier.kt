// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

data class SourceCopyResult(val bytes: Long, val sha256: String?)

/** Один ограниченный проход по источнику; жизненным циклом потоков и fsync управляет вызывающий код. */
object SourceCopier {
    fun copy(input: InputStream, output: OutputStream, maxBytes: Long, computeSha256: Boolean,
        cancelled: () -> Boolean, progress: (Long) -> Unit): SourceCopyResult {
        require(maxBytes >= 0) { "Лимит копирования не может быть отрицательным" }
        val digest = if (computeSha256) MessageDigest.getInstance("SHA-256") else null
        val buffer = ByteArray(65536)
        var bytes = 0L
        fun checkCancelled() {
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Копирование отменено")
        }
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            checkCancelled()
            if (count < 0) break
            if (count == 0) continue
            if (count.toLong() > maxBytes - bytes) throw IOException("Входной файл превышает лимит $maxBytes байт")
            output.write(buffer, 0, count)
            digest?.update(buffer, 0, count)
            bytes += count
            progress(bytes)
        }
        if (bytes == 0L) throw IOException("Выбран пустой файл")
        return SourceCopyResult(bytes, digest?.digest()?.joinToString("") { "%02x".format(it) })
    }
}
