// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.installer

import dev.splitbox.app.model.AppSettings
import dev.splitbox.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

class ObbCancellationTest {
    private class SlowInput : InputStream() {
        private var remaining = 32L * 1024 * 1024
        override fun read(): Int = error("Побайтовое чтение не ожидалось")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            try { Thread.sleep(10) } catch (e: InterruptedException) { throw InterruptedIOException("Передача отменена") }
            if (remaining == 0L) return -1
            return minOf(length.toLong(), remaining, 4096L).toInt().also {
                buffer.fill(42, offset, offset + it)
                remaining -= it
            }
        }
    }
    private fun cancelledCopy(interrupt: Boolean) {
        val root = Files.createTempDirectory("splitbox-obb-cancel").toFile()
        val target = root.resolve("dev.test.game/main.1.dev.test.game.obb").apply { parentFile!!.mkdirs(); writeText("EXISTING-OBB") }
        val other = target.parentFile!!.resolve(".splitbox-unrelated.tmp").apply { writeText("OTHER-OPERATION") }
        val original = ObbPolicy.copyPlan(0, "dev.test.game", 1, target.name, 32L * 1024 * 1024)
        fun local(text: String) = text.replace("/storage/emulated/0/Android/obb", root.absolutePath)
        val plan = original.copy(command = local(original.command), temporaryPath = local(original.temporaryPath),
            cleanupCommand = local(original.cleanupCommand))
        val temporary = File(plan.temporaryPath)
        val runner = ProcessRunner()
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            try { copyShizukuObb(runner, plan, SlowInput(), 32L * 1024 * 1024) }
            catch (e: Throwable) { failure.set(e) }
        }
        try {
            thread.start()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (temporary.length() < 65536 && thread.isAlive && System.nanoTime() < deadline) LockSupport.parkNanos(1_000_000)
            assertTrue("Тест должен дождаться реального частично записанного OBB", temporary.length() >= 65536)
            if (interrupt) thread.interrupt() else runner.cancelAll()
            thread.join(10_000)
            assertFalse("Отмена должна завершить передачу", thread.isAlive)
            assertNotNull("Отменённая передача не считается успешной", failure.get())
            assertEquals("EXISTING-OBB", target.readText())
            assertEquals("OTHER-OPERATION", other.readText())
            assertFalse("Временный файл операции остался: ${temporary.length()} байт", temporary.exists())
        } finally {
            runner.cancelAll(); thread.interrupt(); thread.join(10_000)
            root.deleteRecursively()
        }
    }
    @Test fun rootCoroutineCancellationCleansTemporaryAndKeepsConfirmedApkSuccess() = runBlocking {
        val root = Files.createTempDirectory("splitbox-root-obb-cancel").toFile()
        val preparedDir = root.resolve("prepared").apply { mkdirs() }
        val apk = preparedDir.resolve("base.apk").apply { writeText("APK") }
        val obb = preparedDir.resolve("main.1.dev.test.game.obb").apply {
            java.io.RandomAccessFile(this, "rw").use { it.setLength(32L * 1024 * 1024) }
        }
        val destinationRoot = root.resolve("destination")
        val target = destinationRoot.resolve("dev.test.game/${obb.name}").apply { parentFile!!.mkdirs(); writeText("EXISTING-OBB") }
        val other = target.parentFile!!.resolve(".splitbox-other.tmp").apply { writeText("OTHER") }
        val real = ProcessRunner()
        val runner = object : CommandRunner {
            override fun run(command: List<String>, input: InputStream?, timeoutSeconds: Long): PmResult {
                val shell = command.last()
                if (shell.startsWith("pm install-create")) return PmResult(0, "Success: created install session [42]")
                if (shell.startsWith("pm ")) return PmResult(0, "Success")
                return real.run(listOf("sh", "-c", shell.replace("/storage/emulated/0/Android/obb", destinationRoot.absolutePath)),
                    if (input != null) SlowInput() else null, timeoutSeconds)
            }
        }
        val metadata = ApkMetadata("dev.test.game", 1, "1", 34, 36)
        val entry = ApkEntry(apk, "base.apk", metadata, 3)
        val prepared = PreparedPackage("game.xapk", metadata, listOf(entry), listOf(ObbEntry(obb, obb.name, obb.length())), emptyList(), preparedDir)
        var outcome: BackendOutcome? = null
        val operation = launch {
            outcome = RootInstallBackend(runner).install(InstallRequest(prepared, listOf(entry), AppSettings(installObb = true), "Игра", 0)) { _, _ -> }
        }
        try {
            withTimeout(5000) {
                while (target.parentFile!!.listFiles()!!.none { it.name.startsWith(".splitbox-") && it != other && it.length() >= 65536 }) delay(1)
            }
            operation.cancel()
            withTimeout(10_000) { operation.join() }
            val result = (outcome as BackendOutcome.Finished).result
            assertTrue(result.success)
            assertEquals("APK установлен. Перенос OBB отменён. Используйте экспорт ZIP.", dev.splitbox.core.MessageEnvelope.diagnostic(result.obbWarning.orEmpty()))
            assertEquals("EXISTING-OBB", target.readText())
            assertEquals("OTHER", other.readText())
            assertEquals(setOf(target.name, other.name), target.parentFile!!.listFiles()!!.map { it.name }.toSet())
        } finally { operation.cancel(); real.cancelAll(); operation.join(); root.deleteRecursively() }
    }
    @Test fun cancellationBeforeOpeningTemporaryCannotCreateItAfterCleanup() {
        val root = Files.createTempDirectory("splitbox-obb-early-cancel").toFile()
        val ready = root.resolve("ready")
        val release = root.resolve("release")
        val original = ObbPolicy.copyPlan(0, "dev.test.game", 1, "main.1.dev.test.game.obb", 32L * 1024 * 1024)
        fun local(value: String) = value.replace("/storage/emulated/0/Android/obb", root.absolutePath)
        val plan = original.copy(command = "echo ready > '${ready.absolutePath}'; while [ ! -e '${release.absolutePath}' ]; do sleep 0.01; done; " + local(original.command),
            temporaryPath = local(original.temporaryPath), cleanupCommand = local(original.cleanupCommand))
        val runner = ProcessRunner()
        val thread = Thread { runCatching { copyShizukuObb(runner, plan, SlowInput(), 32L * 1024 * 1024) } }
        try {
            thread.start()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!ready.exists() && thread.isAlive && System.nanoTime() < deadline) LockSupport.parkNanos(1_000_000)
            assertTrue(ready.exists())
            runner.cancelAll()
            thread.join(10_000)
            assertFalse(thread.isAlive)
            release.writeText("release")
            // Даём потенциальному уцелевшему shell пройти барьер: временный путь не должен появиться позднее.
            val lateDeadline = System.nanoTime() + 200_000_000L
            while (System.nanoTime() < lateDeadline) {
                assertFalse(File(plan.temporaryPath).exists())
                LockSupport.parkNanos(1_000_000)
            }
        } finally { release.writeText("release"); runner.cancelAll(); thread.interrupt(); thread.join(10_000); root.deleteRecursively() }
    }
    @Test fun serviceCleanupFailureIsReportedInsteadOfBeingHiddenAfterSuccess() {
        val root = Files.createTempDirectory("splitbox-obb-cleanup-failure").toFile()
        val blocked = root.resolve(".splitbox-owned.tmp").apply { mkdirs(); resolve("occupied").writeText("keep") }
        try {
            val plan = ObbCopyPlan("echo Success", blocked.absolutePath, "unused")
            val error = assertThrows(IllegalStateException::class.java) {
                copyShizukuObb(ProcessRunner(), plan, InputStream.nullInputStream(), 1)
            }
            assertTrue(error.message!!.contains("Не удалось удалить временный OBB"))
            assertEquals("keep", blocked.resolve("occupied").readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun sigkillRemovesOnlyOwnedTemporaryObb() = cancelledCopy(false)
    @Test fun interruptedProcessRemovesOnlyOwnedTemporaryObb() = cancelledCopy(true)
}
