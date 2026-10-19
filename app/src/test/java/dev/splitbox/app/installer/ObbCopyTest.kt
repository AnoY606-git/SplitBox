// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.installer

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.io.File

class ObbCopyTest {
    private fun inRoot(test: (File) -> Unit) {
        val root = Files.createTempDirectory("splitbox-obb-test").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }
    private fun command(root: File, expectedSize: Long) = ObbPolicy.copyCommand(0, "dev.test.game", 99,
        "main.1.dev.test.game.obb", expectedSize).replace("/storage/emulated/0/Android/obb", root.absolutePath)
    @Test fun insufficientDestinationSpacePreservesExistingObbBeforeTransfer() = inRoot { root ->
        val target = File(root, "dev.test.game/main.1.dev.test.game.obb").apply { parentFile!!.mkdirs(); writeText("old") }
        val result = ProcessRunner().run(listOf("sh", "-c", command(root, Long.MAX_VALUE)), "data".byteInputStream())
        assertFalse(result.success)
        assertTrue(result.output, result.output.contains("INSUFFICIENT_STORAGE"))
        assertEquals("old", target.readText())
        assertEquals(listOf(target.name), target.parentFile!!.listFiles()!!.map { it.name })
    }
    @Test fun completeStreamAtomicallyReplacesExistingObb() = inRoot { root ->
        val target = File(root, "dev.test.game/main.1.dev.test.game.obb").apply { requireNotNull(parentFile) { "У тестового файла нет родительского каталога" }.mkdirs(); writeText("old") }
        val result = ProcessRunner().run(listOf("sh", "-c", command(root, 4)), ByteArrayInputStream("data".toByteArray()))
        assertTrue(result.output, result.success)
        assertEquals("data", target.readText())
        assertEquals(listOf(target.name), requireNotNull(target.parentFile) { "У тестового файла нет родительского каталога" }.listFiles()!!.map { it.name })
    }
    @Test fun truncatedStreamPreservesExistingObb() = inRoot { root ->
        val target = File(root, "dev.test.game/main.1.dev.test.game.obb").apply { requireNotNull(parentFile) { "У тестового файла нет родительского каталога" }.mkdirs(); writeText("old") }
        val result = ProcessRunner().run(listOf("sh", "-c", command(root, 10)), ByteArrayInputStream("data".toByteArray()))
        assertFalse(result.success)
        assertEquals("old", target.readText())
        assertEquals(listOf(target.name), requireNotNull(target.parentFile) { "У тестового файла нет родительского каталога" }.listFiles()!!.map { it.name })
    }
    @Test fun symlinkPackageDirectoryCannotEscapeRoot() = inRoot { root ->
        val outside = Files.createTempDirectory("splitbox-outside").toFile()
        try {
            Files.createSymbolicLink(File(root, "dev.test.game").toPath(), outside.toPath())
            val result = ProcessRunner().run(listOf("sh", "-c", command(root, 4)), ByteArrayInputStream("data".toByteArray()))
            assertFalse(result.success)
            assertTrue(outside.listFiles()!!.isEmpty())
        } finally { outside.deleteRecursively() }
    }
    @Test fun symlinkDestinationCannotOverwriteAnotherFile() = inRoot { root ->
        val outside = File(root, "outside").apply { writeText("keep") }
        val target = File(root, "dev.test.game/main.1.dev.test.game.obb").apply { requireNotNull(parentFile) { "У тестового файла нет родительского каталога" }.mkdirs() }
        Files.createSymbolicLink(target.toPath(), outside.toPath())
        val result = ProcessRunner().run(listOf("sh", "-c", command(root, 4)), ByteArrayInputStream("data".toByteArray()))
        assertFalse(result.success)
        assertEquals("keep", outside.readText())
    }
}
