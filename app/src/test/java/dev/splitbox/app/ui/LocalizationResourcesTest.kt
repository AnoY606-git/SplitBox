// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.ui

import java.io.File
import java.util.Locale
import dev.splitbox.core.MessageEnvelope
import dev.splitbox.app.data.InsufficientStorageException
import dev.splitbox.app.data.PackageSizePolicy
import dev.splitbox.app.model.AppLanguage
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Проверяем поставляемый каталог, а не копию строк в тестовом окружении Android. */
class LocalizationResourcesTest {
    private fun strings(directory: String): Map<String, String> {
        val file = File("src/main/res/$directory/strings.xml")
        if (!file.exists()) return emptyMap()
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).associate { nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent }
    }
    @Test fun languageSelectionIsAvailableInEnglishAndRussian() {
        assertEquals("Language", strings("values")["settings_language"])
        assertEquals("Язык", strings("values-ru")["settings_language"])
    }
    @Test fun englishFallbackAndRussianCatalogHaveMatchingArguments() {
        val english = strings("values")
        val russian = strings("values-ru")
        assertTrue("Должны быть переведены все экраны, а не только настройка языка", english.size > 300)
        assertEquals(english.keys, russian.keys)
        val argument = Regex("%[0-9]+\\$[sdf]")
        english.forEach { (key, value) ->
            assertFalse("Русский текст в английском каталоге: $key", Regex("[А-Яа-яЁё]").containsMatchIn(value))
            assertEquals("Параметры строки $key", argument.findAll(value).map { it.value }.toList().sorted(),
                argument.findAll(russian.getValue(key)).map { it.value }.toList().sorted())
        }
    }
    private fun render(value: String, language: String): String {
        val catalog = strings(if (language == "ru") "values-ru" else "values")
        return MessageEnvelope.resolve(value) { key, arguments ->
            catalog[key]?.let { String.format(Locale.forLanguageTag(language), it, *arguments.toTypedArray()) }
        }
    }
    @Test fun runtimeFailureCanChangeLanguageAfterBeingSavedWithoutChangingItsArguments() {
        val failure = assertThrows(InsufficientStorageException::class.java) { PackageSizePolicy.requireSpace(4096, 2048) }
        val file = File.createTempFile("splitbox-localized-error", ".txt")
        try {
            file.writeText(failure.message.orEmpty())
            val saved = file.readText()
            val english = render(saved, "en")
            val russian = render(saved, "ru")
            assertTrue(english.startsWith("Not enough free space:"))
            assertTrue(russian.startsWith("Недостаточно свободного места:"))
            listOf(english, russian).forEach {
                assertTrue(it.contains("4096"))
                assertTrue(it.contains("2048"))
                assertFalse(it.contains("SPLITBOX_MESSAGE"))
            }
            assertEquals(saved, file.readText())
        } finally { file.delete() }
    }
    @Test fun allCatalogFormatsHaveWhitelistedKeysAndAcceptTheirDeclaredArgumentTypes() {
        val pattern = Regex("%(?:([0-9]+)\\$)?[0-9.]*([sdf])")
        for (language in listOf("en", "ru")) {
            strings(if (language == "ru") "values-ru" else "values").forEach { (key, value) ->
                if (key != "app_name") assertNotNull("Неизвестный ключ $key", messageResource(key))
                val arguments = mutableMapOf<Int, Any>()
                var sequential = 0
                pattern.findAll(value).forEach { match ->
                    val index = match.groupValues[1].toIntOrNull()?.minus(1) ?: sequential++
                    arguments[index] = if (match.groupValues[2] == "f") 1.5 else 1L
                }
                val args = Array<Any>((arguments.keys.maxOrNull() ?: -1) + 1) { arguments[it] ?: "" }
                try { String.format(Locale.forLanguageTag(language), value, *args) }
                catch (error: java.util.IllegalFormatException) { fail("Некорректный формат $language/$key: $error") }
            }
        }
    }
    @Test fun languageSelectionPreservesSystemFallbackAndRecognizesRegionalOverrides() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguageTags(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguageTags("de-DE"))
        assertEquals(AppLanguage.RUSSIAN, AppLanguage.fromLanguageTags("ru-RU,en-US"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLanguageTags("en-GB"))
        assertEquals("", AppLanguage.SYSTEM.languageTags)
    }
    @Test fun onePassReportRenderingKeepsExternalAppAndVersionTextLiteralForPreviewAndCopy() {
        val external = dev.splitbox.core.message("settings_language_system", "Система")
        val entry = dev.splitbox.app.model.HistoryEntry("id", 0L, external, "dev.example.app", external,
            "base.apk", dev.splitbox.app.model.BackendMode.SYSTEM, true,
            dev.splitbox.core.message("history_presentation_success", "Успешно"))
        val shownReport = render(formatHistoryReport(entry, "test-version"), "en")
        assertEquals("Оба внешних поля должны остаться буквальными", 3, shownReport.split(external).size)
        assertFalse(shownReport.contains("Follow system"))
        assertTrue("Повторное разрешение меняет внешние данные и недопустимо для preview/copy",
            render(shownReport, "en").contains("Follow system"))
    }
}
