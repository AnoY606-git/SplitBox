// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import android.os.StatFs
import java.io.File

/**
 * Только уже доступные приложению байты, без зарезервированных блоков ФС.
 * Не считаем удаляемый кэш через getAllocatableBytes: в кэше лежат и наши APK,
 * поэтому очистка ради allocateBytes может удалить ещё нужные рабочие данные.
 * Резерв 64 МиБ и обработка ошибок записи остаются в существующей политике.
 */
internal class AvailableStorage(directory: File) {
    private val path = directory.absolutePath
    private val stats = StatFs(path)

    /** Обновляем счётчик перед записью, переиспользуя объект на протяжении копирования. */
    fun bytes(): Long {
        stats.restat(path)
        return stats.availableBytes
    }
}
