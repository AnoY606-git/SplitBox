// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class ManifestVersionNameTest {
    // Первые атрибуты корня CoreFixtures: package, versionCode, versionName.
    private fun withRootAttribute(bytes: ByteArray = CoreFixtures.manifest(), index: Int = 2,
        type: Int = 1, value: Int = 0x7f010000, keepRaw: Boolean = false): ByteArray {
        val result = bytes.copyOf()
        val data = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 8
        while ((data.getShort(offset).toInt() and 0xffff) != 0x102) offset += data.getInt(offset + 4)
        val attribute = offset + 16 + 20 + index * 20
        if (!keepRaw) data.putInt(attribute + 8, -1)
        result[attribute + 15] = type.toByte()
        data.putInt(attribute + 16, value)
        return result
    }
    private fun reject(bytes: ByteArray) {
        try { BinaryManifestReader().read(bytes); fail("Ожидалось отклонение некорректного типа атрибута") }
        catch (_: PreparationException) { }
    }
    @Test fun resourceVersionNameRemainsUnresolvedWithoutLosingIdentity() {
        val metadata = BinaryManifestReader().read(withRootAttribute())
        assertEquals("", metadata.versionName)
        assertEquals("dev.test", metadata.packageName)
        assertEquals(7L, metadata.versionCode)
        assertEquals(23, metadata.minSdk)
        assertEquals(36, metadata.targetSdk)
    }
    @Test fun resourceVersionNameDoesNotUseRawTextAsResolvedValue() {
        assertEquals("", BinaryManifestReader().read(withRootAttribute(keepRaw = true)).versionName)
    }
    @Test fun literalVersionNameIsPreserved() {
        assertEquals("1.7", BinaryManifestReader().read(CoreFixtures.manifest()).versionName)
    }
    @Test fun versionNameStillRejectsOtherTypesAndInvalidStringIndices() {
        for (type in listOf(0, 2, 0x10, 0x12)) reject(withRootAttribute(type = type))
        reject(withRootAttribute(type = 3, value = Int.MAX_VALUE))
    }
    @Test fun identityAndSplitAttributesStillRejectResourceReferences() {
        reject(withRootAttribute(index = 0))
        reject(withRootAttribute(index = 1))
        for (manifest in listOf(CoreFixtures.manifest(versionMajor = 1), CoreFixtures.manifest(split = "camera"),
            CoreFixtures.manifest(configFor = "camera"), CoreFixtures.manifest(requiredTypes = "base__abi"),
            CoreFixtures.manifest(splitTypes = "base__abi"))) {
            reject(withRootAttribute(manifest, index = 3))
        }
    }
}
