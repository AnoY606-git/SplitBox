// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.core

import org.junit.Assert.*
import org.junit.Test

class MessageEnvelopeTest {
    @Test fun nestedMessagesCanBeRenderedAfterPersistenceWithoutTranslatingExternalText() {
        val inner = message("m175", "Недостаточно свободного места")
        val outer = message("m043", "Не удалось сохранить состояние: %1\$s", nestedMessage(inner))
        val render: (String, List<Any?>) -> String? = { key, args -> when (key) {
            "m175" -> "Not enough free space"
            "m043" -> "Could not save state: ${args.single()}"
            else -> null
        } }
        assertEquals("Could not save state: Not enough free space", MessageEnvelope.resolve(outer, render))
        val external = message("m252", "Сообщение: %1\$s", inner)
        assertEquals("Literal: $inner", MessageEnvelope.resolve(external) { _, args -> "Literal: ${args.single()}" })
        assertEquals("Не удалось сохранить состояние: Недостаточно свободного места", MessageEnvelope.diagnostic(outer))
    }
    @Test fun concatenatedMessagesAndPlainNativeDiagnosticsSurvive() {
        val text = message("m175", "Недостаточно свободного места") + ": ENOSPC /data/app"
        assertEquals("No space: ENOSPC /data/app", MessageEnvelope.resolve(text) { _, _ -> "No space" })
        assertEquals("Недостаточно свободного места: ENOSPC /data/app", MessageEnvelope.diagnostic(text))
    }
    @Test fun unknownKeysMalformedDataAndUntrustedTextAreSafe() {
        assertEquals("plain pm Failure [INSTALL_FAILED]", MessageEnvelope.resolve("plain pm Failure [INSTALL_FAILED]") { _, _ -> error("Не вызывать") })
        val unknown = message("future_message", "Диагностика")
        assertEquals("Диагностика", MessageEnvelope.resolve(unknown) { _, _ -> null })
        val broken = "\u001eSPLITBOX_MESSAGE/1|bad"
        assertEquals(broken, MessageEnvelope.resolve(broken) { _, _ -> error("Не вызывать") })
    }
    @Test fun nestingAndFieldLengthsAreBounded() {
        var value = "native diagnostic"
        repeat(100) { value = message("m252", "Сообщение: %1\$s", nestedMessage(value)) }
        val rendered = MessageEnvelope.resolve(value) { key, args -> if (key == "message_details_truncated") "Details truncated" else "Message: ${args.single()}" }
        assertTrue(rendered.length < 100_000)
        assertFalse(rendered.contains("SPLITBOX_MESSAGE/1"))
        assertFalse(Regex("[А-Яа-яЁё]").containsMatchIn(rendered))
        assertTrue(message("m252", "Сообщение: %1\$s", "x".repeat(1_000_000)).length < 100_000)
    }
    @Test fun redactionPreservesNestedKeysAndRemovesUrisFromArgumentsAndDiagnostics() {
        val inner = message("m252", "Сообщение: %1\$s", "failure content://private/source.apk")
        val outer = message("m043", "Ошибка: %1\$s", nestedMessage(inner))
        val redacted = MessageEnvelope.sanitize(outer) {
            it.replace(Regex("(?:content|file)://[^\\s]+"), "[URI]")
        }
        val rendered = MessageEnvelope.resolve(redacted) { key, args ->
            when (key) {
                "m252" -> "Message: ${args.single()}"
                "m043" -> "Error: ${args.single()}"
                else -> null
            }
        }
        assertEquals("Error: Message: failure [URI]", rendered)
        assertFalse(MessageEnvelope.diagnostic(redacted).contains("content://"))
        assertFalse(rendered.contains("private"))
        assertFalse(rendered.contains("SPLITBOX_MESSAGE"))
    }
    private fun english(key: String, args: List<Any?>): String? = when (key) {
        "test", "native" -> args.firstOrNull()?.toString().orEmpty()
        "history_presentation_message_1" -> "Message: ${args.single()}"
        "message_details_truncated" -> (args.firstOrNull()?.toString().orEmpty()) + "… [Details truncated]"
        else -> null
    }
    @Test fun thirtyTwoMultibyteArgumentsTerminateWithinTheWireBudget() {
        val value = message("test", "%1\$s", *Array<Any>(32) { "界".repeat(80) })
        assertTrue(value.toByteArray(Charsets.UTF_8).size <= 8192)
        assertFalse(MessageEnvelope.resolve(value, ::english).contains("SPLITBOX_MESSAGE"))
    }
    @Test fun longNativeFailurePrefixSurvivesHistoryWrappingAndBounds() {
        val native = "INSTALL_FAILED_INVALID_APK: " + "x".repeat(3538)
        val inner = message("test", "Завершение установки") + native
        val wrapped = message("history_presentation_message_1", "Сообщение: %1\$s", nestedMessage(inner))
        for (value in listOf(wrapped, MessageEnvelope.sanitize(wrapped) { it }, MessageEnvelope.limit(wrapped + wrapped, 200))) {
            val rendered = MessageEnvelope.resolve(value, ::english)
            assertTrue(rendered, rendered.contains("INSTALL_FAILED_INVALID_APK"))
            assertFalse(rendered.contains("SPLITBOX_MESSAGE"))
            assertFalse(Regex("[А-Яа-яЁё]").containsMatchIn(rendered))
            assertTrue(value.toByteArray(Charsets.UTF_8).size <= 8192)
        }
    }
    @Test fun deepAndOversizedCompositesRemainLocalizedAfterSanitizeAndLimit() {
        var value = message("native", "%1\$s", "INSTALL_FAILED_INVALID_APK")
        repeat(120) { value = message("history_presentation_message_1", "Сообщение: %1\$s", nestedMessage(value)) }
        val composite = value.repeat(12)
        val candidates = listOf(
            message("history_presentation_message_1", "Сообщение: %1\$s", nestedMessage(composite)),
            MessageEnvelope.sanitize(value) { it },
            MessageEnvelope.limit(composite, 200),
        )
        candidates.forEach {
            val rendered = MessageEnvelope.resolve(it, ::english)
            assertFalse(rendered, Regex("[А-Яа-яЁё]").containsMatchIn(rendered))
            assertFalse(rendered.contains("SPLITBOX_MESSAGE"))
            assertTrue(it.toByteArray(Charsets.UTF_8).size <= 8192)
        }
    }
    @Test fun nativeLimitHonorsTheWireBudgetEvenWhenTheRequestedLimitIsHuge() {
        val limited = MessageEnvelope.limit("INSTALL_FAILED_INVALID_APK: " + "界".repeat(100_000), Int.MAX_VALUE)
        assertTrue(limited.toByteArray(Charsets.UTF_8).size <= 8192)
        assertTrue(MessageEnvelope.resolve(limited, ::english).contains("INSTALL_FAILED_INVALID_APK"))
    }
}
