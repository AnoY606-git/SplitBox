// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.installer

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

class ProcessRunnerTest {
    @Test fun writesStdinWithoutPassingPrivatePath() {
        val result = ProcessRunner().run(listOf("sh", "-c", "cat"), ByteArrayInputStream("binary-payload".toByteArray()))
        assertEquals(0, result.exitCode)
        assertEquals("binary-payload", result.output)
    }
    @Test fun drainsLargeOutputButBoundsRetainedDiagnostic() {
        val result = ProcessRunner().run(listOf("sh", "-c", "yes output | head -c 300000"))
        assertEquals(0, result.exitCode)
        assertEquals(131072, result.output.length)
    }
    @Test fun failureBeforeReadingStdinKeepsPackageManagerDiagnostic() {
        val result = ProcessRunner().run(listOf("sh", "-c", "echo 'Failure [DENIED]'; exit 2"), ByteArrayInputStream(ByteArray(1048576)))
        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("Failure [DENIED]"))
        assertFalse(result.success)
    }
    @Test fun stalledCommandIsTerminatedByDeadline() {
        val start = System.nanoTime()
        assertThrows(IllegalStateException::class.java) {
            ProcessRunner().run(listOf("sh", "-c", "exec sleep 30"), timeoutSeconds = 1)
        }
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 10)
    }
}
