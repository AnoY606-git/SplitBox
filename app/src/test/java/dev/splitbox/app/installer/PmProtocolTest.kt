// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.installer

import org.junit.Assert.*
import org.junit.Test

class PmProtocolTest {
    @Test fun failureTextNeverCountsAsSuccess() {
        assertFalse(PmResult(0, "Failure [INSTALL_FAILED_INVALID_APK]").success)
        assertFalse(PmResult(1, "Success").success)
        assertFalse(PmResult(0, "").success)
        assertTrue(PmResult(0, "Success\n").success)
    }
    @Test fun sessionIdRequiresSuccessfulCreateReply() {
        assertEquals(42, PmProtocol.sessionId(PmResult(0, "Success: created install session [42]\n")))
        for (value in listOf("Failure [42]", "Success [0]", "Success [99999999999999999]", "Success [42] [43]")) {
            assertThrows(IllegalStateException::class.java) { PmProtocol.sessionId(PmResult(0, value)) }
        }
    }
    @Test fun commandsUseGeneratedApkNamesAndStdin() {
        assertEquals("pm install-write -S 123 42 'split_3.apk' -", PmProtocol.write(42, 3, 123))
        assertEquals("pm install-create --user 10 -r -d -S 123", PmProtocol.create(10, 123, true))
        assertThrows(IllegalArgumentException::class.java) { PmProtocol.write(-1, 0, 3) }
        assertThrows(IllegalArgumentException::class.java) { PmProtocol.write(1, 0, 0) }
    }
    @Test fun obbOnlyTargetsMatchingPackageWithIndependentExpansionVersion() {
        assertEquals("/storage/emulated/10/Android/obb/com.example.game/main.7.com.example.game.obb",
            ObbPolicy.destination(10, "com.example.game", 7, "main.7.com.example.game.obb"))
        assertEquals("/storage/emulated/10/Android/obb/com.example.game/main.1.com.example.game.obb",
            ObbPolicy.destination(10, "com.example.game", 7, "main.1.com.example.game.obb"))
        for (name in listOf("../main.7.com.example.game.obb", "main.7.com.other.game.obb", "main.x.com.example.game.obb", "main.7.com.example.game.obb;id")) {
            assertThrows(IllegalArgumentException::class.java) { ObbPolicy.destination(10, "com.example.game", 7, name) }
        }
        assertThrows(IllegalArgumentException::class.java) { ObbPolicy.destination(0, "com.example;id", 7, "main.7.com.example;id.obb") }
    }
}
