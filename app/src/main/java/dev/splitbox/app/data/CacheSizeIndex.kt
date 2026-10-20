// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import dev.splitbox.core.message
import dev.splitbox.core.nestedMessage

import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Сканирование и изменения выполняются на IO; чтение последнего полного итога не ждёт диск. */
internal class CacheSizeIndex(private val root: File,
    private val measureDirectory: (File) -> Long = ::measureCacheEntry) {
    private val ioLock = Any()
    private var sizes = mutableMapOf<String, Long>()
    @Volatile private var total = 0L

    fun bytes(): Long = total

    fun rescan(): Long = synchronized(ioLock) {
        val snapshot = cacheChildren(root).associate { it.name to measureDirectory(it) }.toMutableMap()
        val sum = snapshot.values.fold(0L, Math::addExact)
        sizes = snapshot
        total = sum
        sum
    }

    fun refreshDirectory(directory: File): Long = synchronized(ioLock) {
        checkDirectory(directory)
        refreshLocked(directory)
    }

    fun delete(directory: File) = synchronized(ioLock) {
        checkDirectory(directory)
        try {
            if (!directory.deleteRecursively() && directory.exists()) throw IOException(message("cache_size_index_could_not_clear_the_working_directory", "Не удалось очистить рабочий каталог"))
        } finally {
            // При частичном удалении индекс отражает оставшиеся файлы.
            refreshLocked(directory)
        }
    }

    private fun checkDirectory(directory: File) {
        check(directory.canonicalFile.parentFile == root.canonicalFile && !Files.isSymbolicLink(directory.toPath())) {
            message("cache_size_index_cannot_modify_a_directory_outside_the_cache", "Нельзя изменить каталог вне кэша")
        }
    }

    private fun refreshLocked(directory: File): Long {
        val size = measureDirectory(directory)
        val next = Math.addExact(total - (sizes[directory.name] ?: 0L), size)
        if (size == 0L) sizes.remove(directory.name) else sizes[directory.name] = size
        total = next
        return next
    }
}

private fun cacheChildren(directory: File): Array<File> = directory.listFiles()
    ?: if (!directory.exists()) emptyArray() else throw IOException(message("cache_size_index_could_not_read_the_cache_directory", "Не удалось прочитать каталог кэша"))

private fun measureCacheEntry(entry: File): Long {
    if (Files.isSymbolicLink(entry.toPath())) return 0L
    if (entry.isFile) return entry.length()
    if (!entry.isDirectory) return 0L
    return cacheChildren(entry).fold(0L) { sum, child -> Math.addExact(sum, measureCacheEntry(child)) }
}
