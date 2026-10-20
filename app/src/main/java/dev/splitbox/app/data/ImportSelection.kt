// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import dev.splitbox.core.message
import dev.splitbox.core.nestedMessage
/** Общая проверка выбора SAF и share intent до изменения текущего состояния. */
object ImportSelection {
    fun normalize(uris: List<String>): List<String> {
        val unique = uris.distinct()
        require(unique.isNotEmpty()) { message("import_selection_no_apk_files_selected", "Не выбраны файлы APK") }
        require(unique.size <= 128) { message("import_selection_you_can_select_up_to_128_apk", "Можно выбрать не более 128 файлов APK одного приложения") }
        require(unique.all { it.substringBefore(':') in setOf("content", "file") && ':' in it }) {
            message("split_box_view_model_only_content_file_uris_are_supported", "Поддерживаются только content/file URI")
        }
        return unique
    }
    /** Неизвестный или переполненный размер не становится нулём в индикаторе. */
    fun knownTotal(sizes: List<Long?>): Long? {
        var total = 0L
        sizes.forEach { size ->
            if (size == null || size < 0 || size > Long.MAX_VALUE - total) return null
            total += size
        }
        return total
    }
}
