// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import dev.splitbox.core.ApkEntry
import dev.splitbox.core.validateSelection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class SelectionValidation(val revision: Long = 0, val selected: Set<String> = emptySet(),
    val pending: Boolean = false, val error: String? = null)

/** Тяжёлая проверка выполняется на CPU dispatcher; завершение старого поколения не публикуется. */
class SelectionValidator(private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val validate: suspend (List<ApkEntry>, Set<String>) -> Unit = { entries, paths ->
        validateSelection(entries.map { it.copy(selected = it.archivePath in paths) })
    }) {
    private val lock = Any()
    private var revision = 0L
    private var job: Job? = null
    private val mutable = MutableStateFlow(SelectionValidation())
    val state: StateFlow<SelectionValidation> = mutable.asStateFlow()
    fun submit(entries: List<ApkEntry>, selected: Set<String>): Long = synchronized(lock) {
        val token = ++revision
        job?.cancel()
        val paths = selected.toSet()
        mutable.value = SelectionValidation(token, paths, pending = true)
        job = scope.launch(dispatcher) {
            val error = try { validate(entries, paths); null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { e.message ?: "Не удалось проверить выбор APK" }
            synchronized(lock) {
                if (revision == token) mutable.value = SelectionValidation(token, paths, error = error)
            }
        }
        token
    }
    /** Ждём только запрошенное поколение; любое новое поколение отменяет продолжение разрешения. */
    suspend fun awaitValid(expectedRevision: Long, isFresh: () -> Boolean): Boolean {
        val value = state.first { it.revision != expectedRevision || !it.pending }
        return value.revision == expectedRevision && value.error == null && isFresh()
    }
    fun clear() = synchronized(lock) {
        revision++; job?.cancel(); job = null
        mutable.value = SelectionValidation(revision)
    }
}
