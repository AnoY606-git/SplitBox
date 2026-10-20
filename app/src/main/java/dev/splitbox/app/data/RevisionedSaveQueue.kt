// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import dev.splitbox.core.message
import dev.splitbox.core.nestedMessage

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SaveQueueState(val generation: Long = 0, val revision: Long = 0,
    val savedRevision: Long = 0, val saving: Boolean = false, val error: String? = null)

/** Один последовательный IO writer объединяет ожидающие снимки и подтверждает точную ревизию. */
class RevisionedSaveQueue<T>(scope: CoroutineScope, dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val writer: suspend (T) -> Unit) {
    private data class Request<T>(val generation: Long, val revision: Long, val value: T)
    private val lock = Any()
    private val writing = Mutex()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var latest: Request<T>? = null
    private var pending: Request<T>? = null
    private var generation = 0L
    private var revision = 0L
    private val mutable = MutableStateFlow(SaveQueueState())
    val state: StateFlow<SaveQueueState> = mutable.asStateFlow()
    init {
        scope.launch(dispatcher) {
            for (ignored in signal) {
                while (true) {
                    val request = synchronized(lock) { pending.also { pending = null } } ?: break
                    writing.withLock {
                        val valid = synchronized(lock) { request.generation == generation }
                        if (valid) {
                            val failure = try { writer(request.value); null }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { e }
                            synchronized(lock) {
                                if (request.generation == generation) {
                                    val now = mutable.value
                                    val isLatest = request.revision == now.revision
                                    mutable.value = now.copy(
                                        savedRevision = if (failure == null) maxOf(now.savedRevision, request.revision) else now.savedRevision,
                                        saving = !isLatest,
                                        error = if (isLatest) failure?.message ?: failure?.javaClass?.simpleName else null)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    fun submit(value: T) = synchronized(lock) {
        val request = Request(generation, ++revision, value)
        latest = request; pending = request
        mutable.value = mutable.value.copy(revision = revision, saving = true, error = null)
        signal.trySend(Unit)
        revision
    }
    fun retry() = synchronized(lock) {
        latest?.let { pending = it; mutable.value = mutable.value.copy(saving = true, error = null); signal.trySend(Unit) }
        Unit
    }
    /** Удаление файлов разрешено только после окончания уже начавшейся старой записи. */
    suspend fun invalidateAndAwait() {
        synchronized(lock) {
            generation++; latest = null; pending = null
            mutable.value = SaveQueueState(generation, revision, revision)
        }
        writing.withLock { }
    }
    suspend fun flush() {
        val target = synchronized(lock) { mutable.value }
        val outcome = state.first { it.generation != target.generation || it.savedRevision >= target.revision || (!it.saving && it.error != null) }
        if (outcome.generation != target.generation) throw CancellationException(message("revisioned_save_queue_the_saved_snapshot_was_replaced_by_a", "Снимок сохранения заменён новой операцией"))
        if (outcome.savedRevision < target.revision) throw IOException(outcome.error ?: message("split_box_view_model_could_not_save_state", "Не удалось сохранить состояние"))
    }
}
