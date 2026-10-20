// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.installer

import dev.splitbox.app.model.BackendMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class RootAccessStatus { UNCHECKED, CHECKING, GRANTED, DENIED }
enum class ShizukuAccessStatus { STOPPED, NEEDS_PERMISSION, REQUESTING, GRANTED, DENIED, PERMANENTLY_DENIED }
data class ShizukuAccess(val running: Boolean, val granted: Boolean, val permanentlyDenied: Boolean)
data class AccessSnapshot(val root: RootAccessStatus = RootAccessStatus.UNCHECKED,
    val shizuku: ShizukuAccessStatus = ShizukuAccessStatus.STOPPED)

/** Управляет проверками выбранного способа; не выполняет установку и не меняет режим. */
class AccessCoordinator(
    private val scope: CoroutineScope,
    private val checkRoot: suspend () -> Boolean,
    private val readShizuku: () -> ShizukuAccess,
    private val requestShizuku: (Int) -> Unit,
    private val busy: () -> Boolean,
    private val elapsedMillis: () -> Long,
    private val publish: (AccessSnapshot) -> Unit,
) {
    private var mode = BackendMode.SYSTEM
    private var state = AccessSnapshot()
    private var rootJob: Job? = null
    private var generation = 0L
    private var rootAttempted = false
    private var lastRootCheck: Long? = null
    private var shizukuAttempted = false
    private var shizukuDenied = false
    private var wasRunning = false
    private var nextRequestCode = 901
    private var pendingRequestCode: Int? = null

    // Все входы вызываются на главном потоке, включая callbacks Shizuku через ViewModel.
    fun select(mode: BackendMode) {
        if (this.mode != mode) {
            generation++
            rootJob?.cancel(); rootJob = null
            this.mode = mode
            rootAttempted = false; lastRootCheck = null
            if (pendingRequestCode == null) { shizukuAttempted = false; shizukuDenied = false }
            state = state.copy(root = RootAccessStatus.UNCHECKED)
        }
        refresh()
    }
    fun resumed() = refresh()
    fun binderChanged() = refresh()
    fun binderDied() {
        // Смерть относится к прежнему Binder, даже когда новый уже доступен в API.
        pendingRequestCode = null; shizukuAttempted = false; shizukuDenied = false
        wasRunning = false
        refresh()
    }
    fun permissionResult(requestCode: Int, granted: Boolean) {
        if (requestCode != pendingRequestCode) return
        pendingRequestCode = null
        if (!wasRunning || !readShizuku().running) return
        shizukuAttempted = true
        shizukuDenied = !granted
        refresh()
    }
    fun retry() {
        if (busy()) return
        when (mode) {
            BackendMode.ROOT -> checkRootIfNeeded(force = true)
            BackendMode.SHIZUKU -> {
                if (pendingRequestCode != null) return
                shizukuAttempted = false; shizukuDenied = false; refresh()
            }
            BackendMode.SYSTEM -> refresh()
        }
    }

    private fun refresh() {
        val access = try { readShizuku() } catch (_: Exception) { ShizukuAccess(false, false, false) }
        if (!access.running && wasRunning) {
            shizukuAttempted = false; shizukuDenied = false; pendingRequestCode = null
        }
        wasRunning = access.running
        state = state.copy(shizuku = when {
            !access.running -> ShizukuAccessStatus.STOPPED
            access.granted -> ShizukuAccessStatus.GRANTED
            access.permanentlyDenied -> ShizukuAccessStatus.PERMANENTLY_DENIED
            shizukuDenied -> ShizukuAccessStatus.DENIED
            pendingRequestCode != null -> ShizukuAccessStatus.REQUESTING
            else -> ShizukuAccessStatus.NEEDS_PERMISSION
        })
        publish(state)
        if (busy()) return
        when (mode) {
            BackendMode.ROOT -> checkRootIfNeeded(force = false)
            BackendMode.SHIZUKU -> if (access.running && !access.granted && !access.permanentlyDenied && !shizukuAttempted) {
                shizukuAttempted = true
                val requestCode = nextRequestCode++
                pendingRequestCode = requestCode
                state = state.copy(shizuku = ShizukuAccessStatus.REQUESTING); publish(state)
                try { requestShizuku(requestCode) }
                catch (_: Exception) {
                    pendingRequestCode = null
                    state = state.copy(shizuku = ShizukuAccessStatus.NEEDS_PERMISSION); publish(state)
                }
            }
            BackendMode.SYSTEM -> Unit
        }
    }

    private fun checkRootIfNeeded(force: Boolean) {
        if (rootJob?.isActive == true) return
        val now = elapsedMillis()
        if (!force && rootAttempted && (state.root != RootAccessStatus.GRANTED ||
                    lastRootCheck?.let { now - it < 5000 } == true)) return
        rootAttempted = true
        lastRootCheck = now
        val owner = generation
        state = state.copy(root = RootAccessStatus.CHECKING); publish(state)
        rootJob = scope.launch {
            val granted = try { checkRoot() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            if (owner == generation && mode == BackendMode.ROOT) {
                state = state.copy(root = if (granted) RootAccessStatus.GRANTED else RootAccessStatus.DENIED)
                publish(state)
            }
        }
    }
}
