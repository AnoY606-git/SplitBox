// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.installer

import dev.splitbox.app.model.BackendMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccessCoordinatorTest {
    private class Probe {
        var rootCalls = 0
        var permissionCalls = 0
        val requestCodes = mutableListOf<Int>()
        var running = false
        var granted = false
        var permanent = false
        var rootGranted = false
        var busy = false
        var rootWait: CompletableDeferred<Boolean>? = null
        val states = mutableListOf<AccessSnapshot>()
        fun coordinator(scope: kotlinx.coroutines.CoroutineScope, clock: () -> Long = { 10000L }) = AccessCoordinator(
            scope, { rootCalls++; rootWait?.await() ?: rootGranted },
            { ShizukuAccess(running, granted, permanent) }, { permissionCalls++; requestCodes.add(it) },
            { busy }, clock, states::add,
        )
    }

    @Test fun systemModeNeverRequestsPrivilegedAccess() = runTest {
        val p = Probe(); val c = p.coordinator(backgroundScope)
        c.select(BackendMode.SYSTEM); c.resumed(); c.binderChanged(); runCurrent()
        assertEquals(0, p.rootCalls); assertEquals(0, p.permissionCalls)
    }
    @Test fun selectingRootChecksOnceAndDenialDoesNotLoopOnResume() = runTest {
        val p = Probe(); val c = p.coordinator(backgroundScope)
        c.select(BackendMode.ROOT); runCurrent()
        repeat(3) { c.resumed(); runCurrent() }
        assertEquals(1, p.rootCalls)
        assertEquals(RootAccessStatus.DENIED, p.states.last().root)
        c.retry(); runCurrent(); assertEquals(2, p.rootCalls)
    }
    @Test fun rootGrantIsRecheckedOnResumeAfterCooldown() = runTest {
        var now = 10000L
        val p = Probe().apply { rootGranted = true }; val c = p.coordinator(backgroundScope) { now }
        c.select(BackendMode.ROOT); runCurrent()
        c.resumed(); runCurrent(); assertEquals(1, p.rootCalls)
        now += 5000; p.rootGranted = false; c.resumed(); runCurrent()
        assertEquals(2, p.rootCalls); assertEquals(RootAccessStatus.DENIED, p.states.last().root)
    }
    @Test fun switchingModeDiscardsLateRootResultAndDoesNotDuplicateProbe() = runTest {
        val p = Probe().apply { rootWait = CompletableDeferred() }; val c = p.coordinator(backgroundScope)
        c.select(BackendMode.ROOT); runCurrent(); c.resumed(); c.retry(); runCurrent()
        assertEquals(1, p.rootCalls)
        c.select(BackendMode.SYSTEM); p.rootWait!!.complete(true); runCurrent()
        assertNotEquals(RootAccessStatus.GRANTED, p.states.last().root)
    }
    @Test fun shizukuStartsLaterRequestsOnceAndDenialRequiresExplicitRetry() = runTest {
        val p = Probe(); val c = p.coordinator(backgroundScope)
        c.select(BackendMode.SHIZUKU); assertEquals(0, p.permissionCalls)
        p.running = true; c.binderChanged(); assertEquals(1, p.permissionCalls)
        repeat(3) { c.resumed(); c.binderChanged() }; assertEquals(1, p.permissionCalls)
        c.permissionResult(p.requestCodes.last(), false); c.resumed(); assertEquals(1, p.permissionCalls)
        c.retry(); assertEquals(2, p.permissionCalls)
    }
    @Test fun shizukuGrantedAndPermanentDenialDoNotOpenDialog() = runTest {
        for (granted in listOf(true, false)) {
            val p = Probe().apply { running = true; this.granted = granted; permanent = !granted }
            val c = p.coordinator(backgroundScope); c.select(BackendMode.SHIZUKU); c.retry()
            assertEquals(0, p.permissionCalls)
            assertEquals(if (granted) ShizukuAccessStatus.GRANTED else ShizukuAccessStatus.PERMANENTLY_DENIED, p.states.last().shizuku)
        }
    }
    @Test fun binderDeathClearsGrantAndNewServiceCanRequestAgain() = runTest {
        val p = Probe().apply { running = true; granted = true }; val c = p.coordinator(backgroundScope)
        c.select(BackendMode.SHIZUKU)
        p.running = false; p.granted = false; c.binderChanged()
        assertEquals(ShizukuAccessStatus.STOPPED, p.states.last().shizuku)
        p.running = true; c.binderChanged(); assertEquals(1, p.permissionCalls)
    }
    @Test fun activeInstallationDefersChecksUntilIdleResume() = runTest {
        val p = Probe().apply { busy = true }; val c = p.coordinator(backgroundScope)
        c.select(BackendMode.ROOT); c.resumed(); runCurrent(); assertEquals(0, p.rootCalls)
        p.busy = false; c.resumed(); runCurrent(); assertEquals(1, p.rootCalls)
    }
    @Test fun permissionRequestFailureDoesNotRepeatAutomatically() = runTest {
        var calls = 0
        val snapshots = mutableListOf<AccessSnapshot>()
        val c = AccessCoordinator(backgroundScope, { false }, { ShizukuAccess(true, false, false) },
            { calls++; error("Binder умер") }, { false }, { 0L }, snapshots::add)
        c.select(BackendMode.SHIZUKU); c.resumed()
        assertEquals(1, calls); assertEquals(ShizukuAccessStatus.NEEDS_PERMISSION, snapshots.last().shizuku)
    }
    @Test fun repeatedRetryWhileShizukuDialogIsOpenDoesNotDuplicateRequest() = runTest {
        val p = Probe().apply { running = true }; val c = p.coordinator(backgroundScope)
        c.select(BackendMode.SHIZUKU)
        repeat(3) { c.retry() }
        assertEquals(1, p.permissionCalls)
        assertEquals(ShizukuAccessStatus.REQUESTING, p.states.last().shizuku)
    }
    @Test fun staleDenialAfterBinderDeathDoesNotBlockNewService() = runTest {
        val p = Probe().apply { running = true }; val c = p.coordinator(backgroundScope)
        c.select(BackendMode.SHIZUKU)
        p.running = false; c.binderChanged(); c.permissionResult(p.requestCodes.last(), false)
        p.running = true; c.binderChanged()
        assertEquals(2, p.permissionCalls)
        assertEquals(ShizukuAccessStatus.REQUESTING, p.states.last().shizuku)
    }
    @Test fun modeRoundTripKeepsPendingDialogAndOldServiceCallbackCannotResolveNewOne() = runTest {
        val p = Probe().apply { running = true }; val c = p.coordinator(backgroundScope)
        c.select(BackendMode.SHIZUKU)
        val oldCode = p.requestCodes.last()
        c.select(BackendMode.SYSTEM); c.select(BackendMode.SHIZUKU)
        assertEquals(1, p.permissionCalls)
        p.running = false; c.binderChanged(); p.running = true; c.binderChanged()
        assertEquals(2, p.permissionCalls)
        c.permissionResult(oldCode, false)
        assertEquals(ShizukuAccessStatus.REQUESTING, p.states.last().shizuku)
        p.granted = true; c.permissionResult(p.requestCodes.last(), true)
        assertEquals(ShizukuAccessStatus.GRANTED, p.states.last().shizuku)
    }
    @Test fun nonCancellableOldRootProbeCannotOverwriteNewGeneration() = runTest {
        val first = CompletableDeferred<Boolean>(); val second = CompletableDeferred<Boolean>()
        val states = mutableListOf<AccessSnapshot>(); var calls = 0
        val c = AccessCoordinator(backgroundScope, {
            val result = if (++calls == 1) first else second
            try { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { result.await() } }
            catch (_: kotlinx.coroutines.CancellationException) { result.getCompleted() }
        }, { ShizukuAccess(false, false, false) }, {}, { false }, { 0L }, states::add)
        c.select(BackendMode.ROOT); runCurrent()
        c.select(BackendMode.SYSTEM); c.select(BackendMode.ROOT); runCurrent()
        second.complete(false); runCurrent(); first.complete(true); runCurrent()
        assertEquals(2, calls)
        assertEquals(RootAccessStatus.DENIED, states.last().root)
    }
    @Test fun delayedBinderDeathInvalidatesPendingEvenWhenReplacementAlreadyRuns() = runTest {
        val p = Probe().apply { running = true }; val c = p.coordinator(backgroundScope)
        c.select(BackendMode.SHIZUKU); val oldCode = p.requestCodes.last()
        // MAIN_HANDLER доставил смерть старого Binder после подключения нового.
        c.binderDied()
        assertEquals(2, p.permissionCalls)
        assertNotEquals(oldCode, p.requestCodes.last())
        c.permissionResult(oldCode, false)
        assertEquals(ShizukuAccessStatus.REQUESTING, p.states.last().shizuku)
    }
}
