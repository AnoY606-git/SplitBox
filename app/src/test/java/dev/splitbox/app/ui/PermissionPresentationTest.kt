// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.ui

import dev.splitbox.core.PermissionDeclaration
import org.junit.Assert.*
import org.junit.Test

class PermissionPresentationTest {
    private fun displayRange(permission: PermissionDeclaration) =
        dev.splitbox.core.MessageEnvelope.diagnostic(permissionSdkRange(permission))
    @Test fun nonPositiveMaxSdkShowsRawDeclarationWithoutClaimingAnApplicableSdkRange() {
        for (maxSdk in listOf(0, -1, Int.MIN_VALUE)) {
            for (minSdk in listOf(1, 23)) {
                assertEquals("В декларации: SDK от $minSdk, maxSdkVersion=$maxSdk. Применимость определяет Android",
                    displayRange(PermissionDeclaration("android.permission.CAMERA", minSdk, maxSdk)))
            }
        }
    }
    @Test fun conventionalBoundsAndMissingMaxSdkRemainReadable() {
        assertEquals("SDK 1–32", displayRange(PermissionDeclaration("android.permission.READ_CONTACTS", maxSdk = 32)))
        assertEquals("SDK от 23", displayRange(PermissionDeclaration("android.permission.CAMERA", minSdk = 23)))
        assertEquals("Без ограничений SDK в декларации", displayRange(PermissionDeclaration("android.permission.CAMERA")))
    }
}
