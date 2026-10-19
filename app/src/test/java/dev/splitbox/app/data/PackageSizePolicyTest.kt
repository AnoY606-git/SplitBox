// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import dev.splitbox.app.model.AppSettings
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PackageSizePolicyTest {
    @Test fun defaultAllowsNineGiBWithEnoughDiskAndStillReservesSpace() {
        val budget = PackageSizePolicy.copyBudget(PackageSizePolicy.maxBytes(AppSettings().maxExpandedGiB), 10_737_418_240L)
        assertEquals(10_670_309_376L, budget)
        assertTrue(budget > 9_663_676_417L)
    }
    @Test fun oldDefaultMigratesOnceAndExplicitChoicesSurviveNextRead() {
        assertEquals(0, PackageSizePolicy.restoredLimit(null, false))
        assertEquals(0, PackageSizePolicy.restoredLimit(2, false))
        assertEquals(1, PackageSizePolicy.restoredLimit(1, false))
        assertEquals(4, PackageSizePolicy.restoredLimit(4, false))
        assertEquals(2, PackageSizePolicy.restoredLimit(2, true))
        assertEquals(0, PackageSizePolicy.restoredLimit(99, true))
        listOf(0, 1, 2, 4, 8, 16, 32, 64).forEach { assertEquals(it, PackageSizePolicy.restoredLimit(it, true)) }
    }
    @Test fun explicitLimitStillBoundsActualInputEvenWhenDiskIsLarge() {
        assertEquals(2_147_483_648L, PackageSizePolicy.copyBudget(PackageSizePolicy.maxBytes(2), Long.MAX_VALUE))
        assertEquals(68_719_476_736L, PackageSizePolicy.maxBytes(64))
        assertThrows(IllegalArgumentException::class.java) { PackageSizePolicy.maxBytes(-1) }
    }
    @Test fun fullDiskAndInsufficientSessionSpaceFailWithoutOverflow() {
        assertThrows(IOException::class.java) { PackageSizePolicy.copyBudget(Long.MAX_VALUE, 0) }
        assertThrows(IOException::class.java) { PackageSizePolicy.copyBudget(Long.MAX_VALUE, 67_108_864L) }
        assertThrows(IOException::class.java) { PackageSizePolicy.requireSpace(9_663_676_417L, 9_663_676_417L) }
        assertThrows(IOException::class.java) { PackageSizePolicy.requireSpace(Long.MAX_VALUE, Long.MAX_VALUE) }
        PackageSizePolicy.requireSpace(9_663_676_417L, 9_730_785_281L)
    }
}
