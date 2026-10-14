// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.benchmark

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File

internal const val TARGET = "dev.splitbox.app.benchmark"
private const val TIMEOUT = 30_000L

internal fun MacrobenchmarkScope.requireText(text: String): UiObject2 =
    checkNotNull(device.wait(Until.findObject(By.text(text)), TIMEOUT)) { "Не найден текст: $text" }

/** Вызывать до measureRepeated: pm clear также очищает ART profiles после компиляции. */
internal fun clearTargetData() {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    check(device.executeShellCommand("pm clear $TARGET").trim() == "Success") { "Не удалось очистить тестовый пакет" }
}

internal fun MacrobenchmarkScope.clearTarget() {
    clearTargetData()
    pressHome()
}

internal fun MacrobenchmarkScope.launchMain() {
    startActivityAndWait(Intent(Intent.ACTION_MAIN).apply {
        component = ComponentName(TARGET, "dev.splitbox.app.MainActivity")
        addCategory(Intent.CATEGORY_LAUNCHER)
    })
    requireText("Установка")
}

internal fun MacrobenchmarkScope.navigateSettingsHistoryBack() {
    requireText("Настройки").click()
    requireText("Способ установки, внешний вид и поведение — в одном месте.")
    requireText("История").click()
    requireText("Здесь начинается ваша история")
    // Возврат через настоящую нижнюю навигацию: системный Back закрывает Activity.
    requireText("Установка").click()
    requireText("Выбрать файл")
}

internal fun importIntent(displayName: String = "application.apk"): Intent {
    val context = InstrumentationRegistry.getInstrumentation().context
    val file = File(context.cacheDir, "fixtures/$displayName")
    file.parentFile!!.mkdirs()
    context.assets.open("fixtures/application.apk").use { input -> file.outputStream().use(input::copyTo) }
    val uri = FileProvider.getUriForFile(context, "dev.splitbox.benchmark.fixtures", file)
    return Intent(Intent.ACTION_VIEW).apply {
        component = ComponentName(TARGET, "dev.splitbox.app.MainActivity")
        setDataAndType(uri, "application/vnd.android.package-archive")
        clipData = ClipData.newRawUri("fixture", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }.also(::grantFixtureReadAccess)
}

internal fun grantFixtureReadAccess(intent: Intent) {
    val uri = requireNotNull(intent.data) { "В тестовом Intent отсутствует URI файла" }
    InstrumentationRegistry.getInstrumentation().context
        .grantUriPermission(TARGET, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

internal fun MacrobenchmarkScope.importFixture(intent: Intent) {
    val displayName = requireNotNull(intent.data?.lastPathSegment) { "В тестовом URI отсутствует имя файла" }
    startActivityAndWait(intent)
    // Уникальное имя каждой итерации не позволяет принять предыдущий READY за новый импорт.
    requireText(displayName)
    // Успешный путь реальных copy/parser/signature/SHA; ошибка импорта не считается готовностью.
    checkNotNull(device.wait(Until.findObject(By.text("Установить приложение").enabled(true)), TIMEOUT)) {
        "Импорт не достиг готовности к установке"
    }
    checkNotNull(device.wait(Until.findObject(By.textStartsWith("Выбрано 1 из 1 APK")), TIMEOUT)) {
        "Импорт не подготовил один APK"
    }
}

internal fun MacrobenchmarkScope.launchSplitList(count: Int) {
    startActivityAndWait(Intent().apply {
        component = ComponentName(TARGET, "dev.splitbox.app.benchmark.SplitListBenchmarkActivity")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        putExtra("splitCount", count)
    })
    requireText("Установить приложение")
    checkNotNull(device.wait(Until.findObject(By.textStartsWith("Выбрано $count из $count APK")), TIMEOUT)) {
        "Не загружен синтетический набор $count"
    }
}

internal fun MacrobenchmarkScope.scrollAndToggleSplits(count: Int) {
    val list = checkNotNull(device.wait(Until.findObject(By.scrollable(true)), TIMEOUT)) { "Не найден список частей" }
    val filter = list.scrollUntil(Direction.DOWN, Until.findObject(By.text("Только выбранные")))
    checkNotNull(filter) { "Не найден фильтр частей" }.click()
    // Несколько экранов настоящих Compose-строк; объём жестов одинаков для 50/500/4096.
    repeat(6) { list.scroll(Direction.DOWN, 0.8f) }
    val row = checkNotNull(device.wait(Until.findObject(By.checkable(true).checked(true).enabled(true)), TIMEOUT)) {
        "Не найдена выбранная часть"
    }
    row.click()
    checkNotNull(device.wait(Until.findObject(By.textStartsWith("Выбрано ${count - 1} из $count APK")), TIMEOUT)) {
        "Выбор части не изменился"
    }
    repeat(3) { list.scroll(Direction.UP, 0.8f) }
    requireText("Установить приложение")
}
