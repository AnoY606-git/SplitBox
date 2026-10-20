// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.splitbox.app.MainActivity
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Реальный Android нужен здесь: JVM-заглушки не сохраняют Binder и системный PendingIntent. */
@RunWith(AndroidJUnit4::class)
class ConfirmationIntentStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val saved = mutableListOf<Pair<Int, String>>()

    @After fun cancelSavedConfirmations() {
        val store = ConfirmationIntentStore(context)
        saved.forEach { (sessionId, value) -> store.cancel(sessionId, value) }
    }

    @Test fun binderIntentUsesSystemTokenInsteadOfMarshallingAndSurvivesStoreRecreation() {
        val binder = Binder()
        val original = confirmationIntent(binder)
        val parcel = Parcel.obtain()
        try {
            original.writeToParcel(parcel, 0)
            // Воспроизводим причину регрессии: Parcelable с живым Binder не является дисковым форматом.
            val failure = runCatching { parcel.marshall() }.exceptionOrNull()
            assertTrue("Parcel с Binder должен отклонять marshall", failure is RuntimeException)
        } finally {
            parcel.recycle()
        }

        val value = save(101, original)
        val resolved = ConfirmationIntentStore(context).resolve(101, value)
        assertNotNull("Новый экземпляр хранилища должен найти системный токен", resolved)
        assertTrue("Системное подтверждение должно быть неизменяемым", resolved!!.isImmutable)

        val descriptor = JSONObject(value)
        val filter = Intent.parseUri(descriptor.getString("filter"), Intent.URI_INTENT_SCHEME)
        assertNull("Дисковый дескриптор не должен содержать extras", filter.extras)
        assertTrue("Фильтр должен идентифицировать исходное подтверждение", original.filterEquals(filter))
        assertEquals("Исходный Binder не должен меняться при сохранении", binder,
            original.extras!!.getBinder(BINDER_KEY))

        // Прямой NO_CREATE проверяет наличие токена в Android независимо от экземпляра хранилища.
        val registered = PendingIntent.getActivity(context, descriptor.getInt("requestCode"), filter,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        assertNotNull("Токен должен существовать в системном реестре Android", registered)
        assertEquals(resolved, registered)
    }

    @Test fun cancelRemovesSystemTokenAndCannotRecreateIt() {
        val value = save(102, confirmationIntent())
        assertNotNull(ConfirmationIntentStore(context).resolve(102, value))
        ConfirmationIntentStore(context).cancel(102, value)
        assertNull(ConfirmationIntentStore(context).resolve(102, value))
        ConfirmationIntentStore(context).cancel(102, value)
        assertNull(ConfirmationIntentStore(context).resolve(102, value))

        val descriptor = JSONObject(value)
        val filter = Intent.parseUri(descriptor.getString("filter"), Intent.URI_INTENT_SCHEME)
        assertNull(PendingIntent.getActivity(context, descriptor.getInt("requestCode"), filter,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
    }

    @Test fun mismatchedSessionCannotResolveOrCancelAnotherSessionToken() {
        val value = save(103, confirmationIntent())
        val original = ConfirmationIntentStore(context).resolve(103, value)
        assertNotNull(original)
        assertNull(ConfirmationIntentStore(context).resolve(104, value))
        ConfirmationIntentStore(context).cancel(104, value)
        assertEquals("Чужой sessionId не должен отменять действительное подтверждение", original,
            ConfirmationIntentStore(context).resolve(103, value))
    }

    @Test fun repeatedEqualFiltersHaveDistinctGenerationsAndOldCancellationIsIsolated() {
        val firstIntent = confirmationIntent()
        val secondIntent = Intent(firstIntent).replaceExtras(Bundle().apply { putBinder(BINDER_KEY, Binder()) })
        assertTrue(firstIntent.filterEquals(secondIntent))

        val firstValue = save(105, firstIntent)
        val first = ConfirmationIntentStore(context).resolve(105, firstValue)
        val secondValue = save(105, secondIntent)
        val second = ConfirmationIntentStore(context).resolve(105, secondValue)
        assertNotNull(first)
        assertNotNull(second)
        assertNotEquals("Повторное подтверждение должно иметь своё поколение", firstValue, secondValue)
        assertNotEquals("Равные фильтры не должны подменять системные токены", first, second)

        ConfirmationIntentStore(context).cancel(105, firstValue)
        assertNull(ConfirmationIntentStore(context).resolve(105, firstValue))
        assertEquals("Отмена старого поколения не должна отменять новое", second,
            ConfirmationIntentStore(context).resolve(105, secondValue))
        ConfirmationIntentStore(context).cancel(105, firstValue)
        assertEquals(second, ConfirmationIntentStore(context).resolve(105, secondValue))
    }

    @Test fun legacyAndMalformedDescriptorsCannotCreateOrCancelConfirmation() {
        val value = save(106, confirmationIntent())
        val original = ConfirmationIntentStore(context).resolve(106, value)
        assertNotNull(original)
        val invalid = listOf(
            "",
            "{",
            "null",
            "{}",
            legacyParcelValue(),
            JSONObject(value).put("version", 2).toString(),
            JSONObject(value).put("generation", "не-uuid").toString(),
            JSONObject(value).put("requestCode", JSONObject(value).getInt("requestCode").inv()).toString(),
            JSONObject(value).apply { remove("filter") }.toString()
        )
        invalid.forEach { malformed ->
            assertNull("Повреждённый или старый дескриптор не должен разрешаться",
                ConfirmationIntentStore(context).resolve(106, malformed))
            ConfirmationIntentStore(context).cancel(106, malformed)
        }
        assertEquals(original, ConfirmationIntentStore(context).resolve(106, value))
    }

    private fun confirmationIntent(binder: Binder = Binder()) = Intent(context, MainActivity::class.java)
        .setAction("dev.splitbox.test.CONFIRMATION")
        .setData(Uri.parse("splitbox-test://confirmation/${UUID.randomUUID()}"))
        .putExtras(Bundle().apply {
            putBinder(BINDER_KEY, binder)
            putString("private_extra", "Данные подтверждения остаются в Android")
        })

    private fun save(sessionId: Int, intent: Intent): String = ConfirmationIntentStore(context)
        .save(sessionId, intent).also { saved += sessionId to it }

    private fun legacyParcelValue(): String {
        val parcel = Parcel.obtain()
        return try {
            confirmationIntent().cloneFilter().writeToParcel(parcel, 0)
            Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP)
        } finally {
            parcel.recycle()
        }
    }

    companion object {
        private const val BINDER_KEY = "android.content.pm.extra.INSTALLER_PACKAGE_BINDER"
    }
}
