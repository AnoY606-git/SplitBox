// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.installer

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InputStream

/** Проверяем реальный протокол владения UserService без Android Binder и личного устройства. */
class PrivilegedSessionCommandsTest {
    private class Runner(val commit: () -> PmResult = { PmResult(0, "Success") }) : CommandRunner {
        val commands = mutableListOf<String>()
        override fun run(command: List<String>, input: InputStream?, timeoutSeconds: Long): PmResult {
            assertEquals(listOf("sh", "-c"), command.take(2))
            val pm = command.last()
            commands.add(pm)
            return when {
                pm.startsWith("pm install-create") -> PmResult(0, "Success: created install session [42]")
                pm.startsWith("pm install-commit") -> commit()
                else -> PmResult(0, "Success")
            }
        }
    }
    @Test fun packageAndSingleApkOverEightGiBArePassedToPmWithoutTruncation() {
        val runner = Runner()
        val commands = PrivilegedSessionCommands(runner)
        val bytes = 9663676417L
        val id = commands.createSession(0, bytes, false)
        commands.writeApk(id, 0, bytes, InputStream.nullInputStream())
        assertEquals(listOf("pm install-create --user 0 -r -S 9663676417",
            "pm install-write -S 9663676417 42 'split_0.apk' -"), runner.commands)
    }
    @Test fun createRegistersOwnershipForDisconnectCleanup() {
        val runner = Runner()
        val commands = PrivilegedSessionCommands(runner)
        assertEquals(42, commands.createSession(0, 3, false))
        commands.abandonAll()
        assertEquals(listOf("pm install-create --user 0 -r -S 3", "pm install-abandon 42"), runner.commands)
    }
    @Test fun foreignSessionsCannotBeWrittenCommittedOrAbandoned() {
        val runner = Runner()
        val commands = PrivilegedSessionCommands(runner)
        commands.createSession(0, 3, false)
        assertTrue(runCatching { commands.writeApk(43, 0, 3, InputStream.nullInputStream()) }.isFailure)
        assertTrue(runCatching { commands.commitSession(43) }.isFailure)
        commands.abandonSession(43)
        commands.abandonAll()
        assertFalse(runner.commands.any { it.endsWith("43") || it.contains(" 43 ") })
        assertEquals(1, runner.commands.count { it == "pm install-abandon 42" })
    }
    @Test fun transportLossDuringCommitDoesNotPermitAbandonOrRetry() {
        val runner = Runner { throw IOException("Связь потеряна") }
        val commands = PrivilegedSessionCommands(runner)
        commands.createSession(0, 3, false)
        assertTrue(runCatching { commands.commitSession(42) }.exceptionOrNull() is IOException)
        commands.abandonAll()
        commands.abandonSession(42)
        assertTrue(runCatching { commands.commitSession(42) }.isFailure)
        assertEquals(listOf("pm install-create --user 0 -r -S 3", "pm install-commit 42"), runner.commands)
    }
    @Test fun successAndUnconfirmedResponseAreNeverAbandoned() {
        listOf(PmResult(0, "Success"), PmResult(0, ""), PmResult(1, "Success")).forEach { result ->
            val runner = Runner { result }
            val commands = PrivilegedSessionCommands(runner)
            commands.createSession(0, 3, false)
            val output = commands.commitSession(42)
            assertEquals(if (result.exitCode == 0) result.output else "", output)
            commands.abandonAll()
            assertFalse(runner.commands.contains("pm install-abandon 42"))
        }
    }
    @Test fun provenPmFailureIsReturnedAndAllowsOwnedCleanup() {
        val runner = Runner { PmResult(1, "Failure [INSTALL_FAILED_INVALID_APK]") }
        val commands = PrivilegedSessionCommands(runner)
        commands.createSession(0, 3, false)
        assertEquals("Failure [INSTALL_FAILED_INVALID_APK]", commands.commitSession(42))
        commands.abandonAll()
        assertTrue(runner.commands.contains("pm install-abandon 42"))
    }
    @Test fun invalidWriteOrSizeIsRejectedBeforeAnyPrivilegedCommand() {
        val runner = Runner()
        val commands = PrivilegedSessionCommands(runner)
        assertTrue(runCatching { commands.createSession(0, 0, false) }.isFailure)
        assertTrue(runCatching { commands.createSession(0, -1, false) }.isFailure)
        assertTrue(runner.commands.isEmpty())
        commands.createSession(0, 3, false)
        assertTrue(runCatching { commands.writeApk(42, 4096, 3, InputStream.nullInputStream()) }.isFailure)
        assertTrue(runCatching { commands.writeApk(42, 0, 0, InputStream.nullInputStream()) }.isFailure)
        assertEquals(1, runner.commands.size)
    }
    @Test fun incompleteOrContradictoryFailureNeverRestoresCleanupOwnership() {
        listOf("Failure [", "Failure [INSTALL_FAILED_", "Failure []", "Failure [ ]",
            "Success\nFailure [INSTALL_FAILED_INVALID_APK]", "Failure [INSTALL_FAILED_INVALID_APK]\nSuccess",
            "Success: установка\nFailure [INSTALL_FAILED_INVALID_APK]",
            "Failure [INSTALL_FAILED_INVALID_APK]\nFailure [INSTALL_FAILED_ABORTED]",
            "Failure [INSTALL_FAILED_INVALID_APK]\nОтвет оборван").forEach { output ->
            val runner = Runner { PmResult(1, output) }
            val commands = PrivilegedSessionCommands(runner)
            commands.createSession(0, 3, false)
            assertEquals("Ответ: $output", "", commands.commitSession(42))
            commands.abandonAll()
            commands.abandonSession(42)
            assertTrue(runCatching { commands.commitSession(42) }.isFailure)
            assertEquals(1, runner.commands.count { it == "pm install-commit 42" })
            assertFalse("Ответ: $output", runner.commands.contains("pm install-abandon 42"))
        }
    }

}
