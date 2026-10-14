// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.core

import org.junit.Assert.*
import org.junit.Test

class ManifestPermissionsTest {
    @Test fun readsBinaryDeclarationsWithSdkBoundsAndAliasesFromBaseAndSplit() {
        for (split in listOf(null, "feature.camera")) {
            val declarations = BinaryManifestReader().read(CoreFixtures.manifest(split = split, permissions = listOf(
                CoreFixtures.Permission("android.permission.CAMERA"),
                CoreFixtures.Permission("android.permission.READ_CONTACTS", maxSdk = 32),
                CoreFixtures.Permission("android.permission.ACCESS_FINE_LOCATION", "uses-permission-sdk-23"),
                CoreFixtures.Permission("android.permission.ACCESS_COARSE_LOCATION", "uses-permission-sdk-m"),
                CoreFixtures.Permission("android.permission.CAMERA"),
            ))).requestedPermissions
            assertEquals(setOf(
                PermissionDeclaration("android.permission.CAMERA"),
                PermissionDeclaration("android.permission.READ_CONTACTS", maxSdk = 32),
                PermissionDeclaration("android.permission.ACCESS_FINE_LOCATION", minSdk = 23),
                PermissionDeclaration("android.permission.ACCESS_COARSE_LOCATION", minSdk = 23),
            ), declarations!!.toSet())
        }
    }
    @Test fun newlyReadManifestWithNoDeclarationsIsKnownEmptyButOldMetadataIsUnknown() {
        assertEquals(emptyList<PermissionDeclaration>(), BinaryManifestReader().read(CoreFixtures.manifest()).requestedPermissions)
        assertNull(ApkMetadata("dev.test", 7, "1.7", 23, 36).requestedPermissions)
    }
    @Test fun permissionNameRequiresAndroidNamespaceAndTypedStringEvenWithRawText() {
        for (permission in listOf(CoreFixtures.Permission("android.permission.CAMERA", namespace = "urn:spoof"),
            CoreFixtures.Permission("android.permission.CAMERA", type = 0x10),
            CoreFixtures.Permission("android.permission.CAMERA", type = 1))) {
            try {
                BinaryManifestReader().read(CoreFixtures.manifest(permissions = listOf(permission)))
                fail("Ожидалось отклонение некорректного имени разрешения")
            } catch (_: PreparationException) { }
        }
    }
}
