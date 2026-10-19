// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.installer

import java.io.InputStream
import java.util.concurrent.CancellationException

/** Передача 9 ГиБ может занимать часы. Срок конечный и рассчитан на 256 КиБ/с плюс две минуты. */
internal object TransferPolicy {
    fun timeoutSeconds(bytes: Long): Long {
        require(bytes > 0) { "Размер передачи должен быть положительным" }
        return 120L + bytes / 262144L + if (bytes % 262144L == 0L) 0L else 1L
    }
    fun binderTimeoutMs(bytes: Long): Long = Math.multiplyExact(timeoutSeconds(bytes) + 30L, 1000L)
}

/** Ограничивает частоту UI/Binder обновлений; числа переданных байтов остаются Long. */
internal class TransferProgress(private val total: Long?, private val clock: () -> Long = System::nanoTime,
    private val emit: (Long, Float?) -> Unit) {
    private var lastTime: Long? = null
    private var lastBytes = -1L
    @Synchronized fun report(bytes: Long) {
        if (bytes <= lastBytes) return
        val now = clock()
        if (lastTime != null && bytes != total && now - lastTime!! < 100_000_000L) return
        lastTime = now
        lastBytes = bytes
        emit(bytes, total?.takeIf { it > 0 }?.let { (bytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat() })
    }
}

internal class ProgressInputStream(private val input: InputStream, private val progress: (Long) -> Unit) : InputStream() {
    private var bytes = 0L
    private fun active() { if (Thread.currentThread().isInterrupted) throw CancellationException("Передача отменена") }
    override fun read(): Int {
        active()
        return input.read().also { active(); if (it >= 0) { bytes = Math.addExact(bytes, 1L); progress(bytes) } }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        active()
        return input.read(buffer, offset, length).also { active(); if (it > 0) { bytes = Math.addExact(bytes, it.toLong()); progress(bytes) } }
    }
    override fun close() = input.close()
}
