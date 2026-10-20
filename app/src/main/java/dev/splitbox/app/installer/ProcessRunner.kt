// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.installer

import dev.splitbox.core.message
import dev.splitbox.core.nestedMessage

import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

interface CommandRunner {
    fun run(command: List<String>, input: InputStream? = null, timeoutSeconds: Long = 120): PmResult
}

/** Параллельное чтение потоков исключает взаимную блокировку; таймаут или прерывание завершают дочерний процесс. */
class ProcessRunner : CommandRunner {
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<Process>()
    fun cancelAll() { active.forEach { it.destroyForcibly() } }
    override fun run(command: List<String>, input: InputStream?, timeoutSeconds: Long): PmResult {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        active.add(process)
        val workers = Executors.newFixedThreadPool(2) { r -> Thread(r, "splitbox-process").apply { isDaemon = true } }
        try {
            val output = workers.submit(Callable {
                process.inputStream.use { stream ->
                    val kept = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (kept.size() < 131072) kept.write(buffer, 0, minOf(count, 131072 - kept.size()))
                    }
                    kept.toString(Charsets.UTF_8.name())
                }
            })
            val writer = workers.submit(Callable {
                process.outputStream.use { sink -> input?.copyTo(sink, 65536) }
            })
            check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { message("process_runner_command_timed_out_s", "Превышено время ожидания команды (%1\$s с)", timeoutSeconds) }
            val result = PmResult(process.exitValue(), output.get(5, TimeUnit.SECONDS))
            try { writer.get(5, TimeUnit.SECONDS) }
            catch (e: Exception) {
                if (result.success) throw java.io.IOException(message("process_runner_apk_transfer_was_not_completed", "Передача APK не завершена: %1\$s", result.output.take(1000)), e)
            }
            return result
        } finally {
            var interrupted = Thread.interrupted()
            try {
                process.destroy()
                if (process.isAlive) process.destroyForcibly()
                // Возврат из runner означает, что основной shell уже не может создать временный файл.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (process.isAlive) {
                    val remaining = deadline - System.nanoTime()
                    check(remaining > 0) { message("process_runner_could_not_terminate_the_child_process_within", "Не удалось завершить дочерний процесс за 5 секунд") }
                    try { process.waitFor(remaining, TimeUnit.NANOSECONDS) }
                    catch (_: InterruptedException) { interrupted = true }
                }
            } finally {
                workers.shutdownNow()
                active.remove(process)
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }
}
