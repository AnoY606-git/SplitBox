// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.inspector

import dev.splitbox.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PackageInspectorTest {
    private val camera = PermissionDeclaration("android.permission.CAMERA")
    private val contacts = PermissionDeclaration("android.permission.READ_CONTACTS", maxSdk = 32)
    private fun entry(path: String, permissions: List<PermissionDeclaration>?) = ApkEntry(File(path), path,
        ApkMetadata("dev.test", 5_000_000_000L, "older-looking-name", 34, 36, requestedPermissions = permissions), 10)
    private fun prepared(vararg entries: ApkEntry) = PreparedPackage("test.apks", entries.first().metadata,
        entries.toList(), emptyList(), emptyList(), File("/cache/package"))

    @Test fun comparesLongCodesRatherThanNamesAndKeepsBothVersions() {
        val incoming = PackageVersion("older-looking-name", 5_000_000_000L)
        for ((code, expected) in listOf(4_999_999_999L to VersionRelation.UPDATE,
            5_000_000_000L to VersionRelation.SAME, 5_000_000_001L to VersionRelation.DOWNGRADE)) {
            val installed = PackageVersion("newer-looking-name", code)
            val result = compareVersions(incoming, InstalledPackageResult.Found(installed))
            assertEquals(expected, result.relation)
            assertEquals(incoming, result.incoming)
            assertEquals(installed, result.installed)
        }
    }
    @Test fun unavailableAndFailureAreDifferentWithoutClaimingPackageAbsence() {
        val incoming = PackageVersion("", Long.MAX_VALUE)
        assertEquals(VersionRelation.UNAVAILABLE, compareVersions(incoming, InstalledPackageResult.Unavailable).relation)
        assertEquals(VersionRelation.FAILURE, compareVersions(incoming, InstalledPackageResult.Failure).relation)
    }
    @Test fun selectedApksOnlyAreMergedSortedAndDeduplicatedByNameWithBoundsPreserved() {
        val sdk23Camera = camera.copy(minSdk = 23)
        val p = prepared(entry("base.apk", listOf(contacts, camera, camera)),
            entry("feature.apk", listOf(sdk23Camera)), entry("excluded.apk", null))
        val summary = summarizePermissions(p, setOf("base.apk", "feature.apk"))
        assertTrue(summary.complete)
        assertEquals(listOf(camera.name, contacts.name), summary.permissions.map { it.name })
        assertEquals(listOf(camera, sdk23Camera), summary.permissions.first().declarations)
        assertEquals(listOf(contacts), summary.permissions.last().declarations)
        assertEquals(listOf(contacts.name), summary.search("read_CONTACTS").map { it.name })
        assertEquals(emptyList<PermissionGroup>(), summary.search("absent"))
    }
    @Test fun oldOrPartialMetadataNeverClaimsThereAreNoPermissions() {
        assertFalse(summarizePermissions(prepared(entry("base.apk", null)), setOf("base.apk")).complete)
        val partial = summarizePermissions(prepared(entry("base.apk", listOf(camera)), entry("split.apk", null)), setOf("base.apk", "split.apk"))
        assertFalse(partial.complete)
        assertEquals(listOf(camera.name), partial.permissions.map { it.name })
        assertTrue(summarizePermissions(prepared(entry("base.apk", emptyList())), setOf("base.apk")).complete)
        assertFalse(summarizePermissions(prepared(entry("base.apk", emptyList())), setOf("missing.apk")).complete)
    }
    @Test fun loaderReadsExactPackageOnWorkerAndCancellationDoesNotPublishResult() = runTest {
        val p = prepared(entry("base.apk", listOf(camera)))
        var read = ""
        val dispatcher = StandardTestDispatcher(testScheduler)
        val reader = InstalledPackageReader { name -> read = name; InstalledPackageResult.Unavailable }
        val load = async { loadPackageInspection(p, setOf("base.apk"), reader, dispatcher) }
        assertEquals("", read)
        runCurrent()
        assertEquals("dev.test", read)
        assertEquals(VersionRelation.UNAVAILABLE, load.await().comparison.relation)
        val cancelled = async { loadPackageInspection(p, setOf("base.apk"), reader, dispatcher) }
        cancelled.cancel()
        runCurrent()
        assertTrue(cancelled.isCancelled)
    }
    @Test fun cancelledNonCooperativeReaderCannotReturnStaleInspection() = runTest {
        val p = prepared(entry("base.apk", listOf(camera)))
        lateinit var task: Deferred<PackageInspection>
        val reader = InstalledPackageReader {
            task.cancel()
            InstalledPackageResult.Found(PackageVersion("2", 2))
        }
        task = async { loadPackageInspection(p, setOf("base.apk"), reader, StandardTestDispatcher(testScheduler)) }
        runCurrent()
        assertTrue(task.isCancelled)
        try { task.await(); fail("Отменённый запрос не должен возвращать сведения") }
        catch (_: CancellationException) { }
    }
}
