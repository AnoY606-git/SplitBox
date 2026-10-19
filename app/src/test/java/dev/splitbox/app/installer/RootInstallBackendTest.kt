// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.installer

import dev.splitbox.app.model.AppSettings
import dev.splitbox.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class RootInstallBackendTest {
    private fun fixture(test: (InstallRequest) -> Unit) {
        val directory = Files.createTempDirectory("splitbox-root-test").toFile()
        try {
            val file = directory.resolve("base.apk").apply { writeText("apk") }
            val metadata = ApkMetadata("dev.test.game", 1, "1", 34, 36)
            val apk = ApkEntry(file, "base.apk", metadata, file.length())
            test(InstallRequest(PreparedPackage("game.apk", metadata, listOf(apk), emptyList(), emptyList(), directory),
                listOf(apk), AppSettings(), "Игра", 0))
        } finally { directory.deleteRecursively() }
    }
    private class Runner(val action: (String) -> PmResult = { PmResult(0, "Success") }) : CommandRunner {
        val commands = CopyOnWriteArrayList<String>()
        override fun run(command: List<String>, input: InputStream?, timeoutSeconds: Long): PmResult {
            val pm = command.last()
            commands.add(pm)
            return if (pm.startsWith("pm install-create")) PmResult(0, "Success: created install session [42]") else action(pm)
        }
    }
    @Test fun slowLargeTransferReceivesDeadlineProportionalToSize() = fixture { request -> runBlocking {
        java.io.RandomAccessFile(request.apks.single().file, "rw").use { it.setLength(9663676417L) }
        var writeTimeout = 0L
        var writeCommand = ""
        val runner = object : CommandRunner {
            override fun run(command: List<String>, input: InputStream?, timeoutSeconds: Long): PmResult {
                if (command.last().startsWith("pm install-create")) return PmResult(0, "Success: created install session [42]")
                if (command.last().startsWith("pm install-write")) { writeTimeout = timeoutSeconds; writeCommand = command.last() }
                return PmResult(0, "Success")
            }
        }
        RootInstallBackend(runner).install(request) { _, _ -> }
        assertTrue("9 ГиБ на медленном носителе не должны обрываться через 120 секунд", writeTimeout >= 36900L)
        assertEquals("pm install-write -S 9663676417 42 'split_0.apk' -", writeCommand)
    } }
    @Test fun obbStageBecomesCancellableOnlyAfterConfirmedCommitAndKeepsApkSuccess() = fixture { request -> runBlocking {
        val obbFile = request.prepared.workDir.resolve("main.1.dev.test.game.obb").apply { writeText("obb") }
        val prepared = request.prepared.copy(obbFiles = listOf(ObbEntry(obbFile, obbFile.name, 3)))
        val phases = mutableListOf<String>()
        var finished: BackendOutcome? = null
        val operation = launch {
            finished = RootInstallBackend(Runner()).install(request.copy(prepared = prepared,
                settings = request.settings.copy(installObb = true), onCommitStarted = { phases += "commit" },
                onCommitted = { phases += "confirmed" }, onObbCopyStarted = { phases += "obb"; this@launch.cancel() })) { _, _ -> }
        }
        operation.join()
        assertEquals(listOf("commit", "confirmed", "obb"), phases)
        val result = (finished as BackendOutcome.Finished).result
        assertTrue(result.success)
        assertEquals("APK установлен. Перенос OBB отменён. Используйте экспорт ZIP.", result.obbWarning)
    } }
    @Test fun failedOwnedTemporaryCleanupBecomesObbWarningAfterConfirmedApkSuccess() = fixture { request -> runBlocking {
        val obbFile = request.prepared.workDir.resolve("main.1.dev.test.game.obb").apply { writeText("obb") }
        val prepared = request.prepared.copy(obbFiles = listOf(ObbEntry(obbFile, obbFile.name, 3)))
        val runner = Runner { if (it.startsWith("rm -f -- ")) PmResult(1, "Error [DENIED]") else PmResult(0, "Success") }
        val outcome = RootInstallBackend(runner).install(request.copy(prepared = prepared,
            settings = request.settings.copy(installObb = true))) { _, _ -> } as BackendOutcome.Finished
        assertTrue(outcome.result.success)
        assertTrue(outcome.result.obbWarning!!.contains("Не удалось удалить временный OBB"))
    } }
    @Test fun cancellationInsideSessionCallbackStillAbandonsOwnedSession() = fixture { request -> runBlocking {
        val runner = Runner()
        var receivedId: Int? = null
        val outcome = CompletableDeferred<Throwable?>()
        val operation = launch {
            try {
                RootInstallBackend(runner).install(request.copy(onSessionCreated = {
                    receivedId = it
                    // Отменяем настоящую Job до возврата create из IO в вызывающую корутину.
                    this@launch.cancel()
                })) { _, _ -> }
                outcome.complete(null)
            } catch (e: Throwable) { outcome.complete(e) }
        }
        operation.join()
        assertEquals(42, receivedId)
        assertTrue(outcome.await() is CancellationException)
        assertTrue(runner.commands.toString(), runner.commands.contains("pm install-abandon 42"))
        assertFalse(runner.commands.any { it.startsWith("pm install-write") || it.startsWith("pm install-commit") })
    } }
    @Test fun commitTransportLossIsUnknownAndIsNeverAbandonedOrRetried() = fixture { request -> runBlocking {
        val runner = Runner { if (it.startsWith("pm install-commit")) throw IOException("Связь потеряна") else PmResult(0, "Success") }
        var started = false
        var committed = false
        val error = runCatching {
            RootInstallBackend(runner).install(request.copy(onCommitStarted = { started = true }, onCommitted = { committed = true })) { _, _ -> }
        }.exceptionOrNull()
        assertTrue(error.toString(), error is UncertainInstallException)
        assertEquals("dev.test.game", (error as UncertainInstallException).packageName)
        assertTrue(started)
        assertFalse(committed)
        assertEquals(1, runner.commands.count { it == "pm install-commit 42" })
        assertFalse(runner.commands.contains("pm install-abandon 42"))
    } }
    @Test fun unconfirmedCommitOutputCannotInventFailure() = fixture { request -> runBlocking {
        val runner = Runner { PmResult(0, if (it.startsWith("pm install-commit")) "" else "Success") }
        val error = runCatching { RootInstallBackend(runner).install(request) { _, _ -> } }.exceptionOrNull()
        assertTrue(error.toString(), error is UncertainInstallException)
        assertFalse(runner.commands.contains("pm install-abandon 42"))
    } }
    @Test fun explicitPmFailureRemainsKnownFailure() = fixture { request -> runBlocking {
        val runner = Runner { PmResult(if (it.startsWith("pm install-commit")) 1 else 0,
            if (it.startsWith("pm install-commit")) "Failure [INSTALL_FAILED_INVALID_APK]" else "Success") }
        val error = runCatching { RootInstallBackend(runner).install(request) { _, _ -> } }.exceptionOrNull()
        assertNotNull(error)
        assertFalse(error is UncertainInstallException)
        assertTrue(runner.commands.contains("pm install-abandon 42"))
    } }
    @Test fun successfulCommitIsNotAbandonedWhenDurableCallbackFails() = fixture { request -> runBlocking {
        val runner = Runner()
        val error = runCatching { RootInstallBackend(runner).install(request.copy(onCommitted = { throw IOException("Диск заполнен") })) { _, _ -> } }.exceptionOrNull()
        assertTrue(error is IOException)
        assertFalse(error is UncertainInstallException)
        assertFalse(runner.commands.contains("pm install-abandon 42"))
    } }
    @Test fun failedCommitBarrierPreventsCommitAndAbandonsOwnedSession() = fixture { request -> runBlocking {
        val runner = Runner()
        val error = runCatching { RootInstallBackend(runner).install(request.copy(onCommitStarted = { throw IOException("Диск заполнен") })) { _, _ -> } }.exceptionOrNull()
        assertTrue(error is IOException)
        assertFalse(error is UncertainInstallException)
        assertFalse(runner.commands.contains("pm install-commit 42"))
        assertTrue(runner.commands.contains("pm install-abandon 42"))
    } }
    @Test fun cancellationDuringSuccessfulCommitPreservesProofAndDoesNotAbandon() = fixture { request -> runBlocking {
        var committed = false
        val runner = Runner()
        val operation = launch {
            RootInstallBackend(runner).install(request.copy(onCommitStarted = { this@launch.cancel() }, onCommitted = { committed = true })) { _, _ -> }
        }
        operation.join()
        assertTrue(committed)
        assertTrue(runner.commands.contains("pm install-commit 42"))
        assertFalse(runner.commands.contains("pm install-abandon 42"))
    } }
    @Test fun cancellationBeforeCommitBarrierPreventsCommit() = fixture { request -> runBlocking {
        val runner = Runner()
        var started = false
        val operation = launch {
            RootInstallBackend(runner).install(request.copy(onCommitStarted = { started = true })) { stage, _ ->
                if (stage == "Root: установка") this@launch.cancel()
            }
        }
        operation.join()
        assertFalse(started)
        assertFalse(runner.commands.contains("pm install-commit 42"))
        assertTrue(runner.commands.contains("pm install-abandon 42"))
    } }
    @Test fun timeoutAndInterruptionDuringCommitRemainUnknown() = fixture { request -> runBlocking {
        listOf(InterruptedException("Прервано"), CancellationException("Отменено"),
            IllegalStateException("Превышено время ожидания команды")).forEach { cause ->
            val runner = Runner { if (it.startsWith("pm install-commit")) throw cause else PmResult(0, "Success") }
            val error = runCatching { RootInstallBackend(runner).install(request) { _, _ -> } }.exceptionOrNull()
            assertTrue(error.toString(), error is UncertainInstallException)
            assertEquals(cause.javaClass, error!!.cause!!.javaClass)
            assertEquals(cause.message, error.cause!!.message)
            assertFalse(runner.commands.contains("pm install-abandon 42"))
        }
    } }
    @Test fun sessionCallbackWriteFailureStillCleansOwnedSession() = fixture { request -> runBlocking {
        val runner = Runner()
        val error = runCatching { RootInstallBackend(runner).install(request.copy(onSessionCreated = { throw IOException("Диск заполнен") })) { _, _ -> } }.exceptionOrNull()
        assertTrue(error is IOException)
        assertFalse(runner.commands.contains("pm install-commit 42"))
        assertTrue(runner.commands.contains("pm install-abandon 42"))
    } }

    @Test fun incompleteOrContradictoryFailureRemainsUnknownWithoutAbandonOrRetry() = fixture { request -> runBlocking {
        listOf("Failure [", "Failure [INSTALL_FAILED_", "Failure []", "Failure [ ]",
            "Success\nFailure [INSTALL_FAILED_INVALID_APK]", "Failure [INSTALL_FAILED_INVALID_APK]\nSuccess",
            "Success: установка\nFailure [INSTALL_FAILED_INVALID_APK]",
            "Failure [INSTALL_FAILED_INVALID_APK]\nFailure [INSTALL_FAILED_ABORTED]",
            "Failure [INSTALL_FAILED_INVALID_APK]\nОтвет оборван").forEach { output ->
            val runner = Runner { if (it.startsWith("pm install-commit")) PmResult(1, output) else PmResult(0, "Success") }
            var committed = false
            val error = runCatching {
                RootInstallBackend(runner).install(request.copy(onCommitted = { committed = true })) { _, _ -> }
            }.exceptionOrNull()
            assertTrue("Ответ: $output; исключение: $error", error is UncertainInstallException)
            assertFalse(committed)
            assertEquals(1, runner.commands.count { it == "pm install-commit 42" })
            assertFalse("Ответ: $output", runner.commands.contains("pm install-abandon 42"))
        }
    } }

}
