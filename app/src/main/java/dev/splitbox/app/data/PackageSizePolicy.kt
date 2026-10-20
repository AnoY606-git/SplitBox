// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import dev.splitbox.core.message
import dev.splitbox.core.nestedMessage

import dev.splitbox.core.PREPARATION_SPACE_RESERVE_BYTES
import java.io.IOException

internal class InsufficientStorageException(required: Long, available: Long) : IOException(
    message("package_size_policy_not_enough_free_space_an_additional_bytes", "Недостаточно свободного места: требуется ещё %1\$s байт и резерв 64 МиБ, доступно %2\$s байт. Освободите место на устройстве.", required, available.coerceAtLeast(0)))

/** Ноль означает автоматическое ограничение свободным местом, остальные значения — явный лимит. */
object PackageSizePolicy {
    val choicesGiB = listOf(0, 1, 2, 4, 8, 16, 32, 64)
    fun maxBytes(limitGiB: Int): Long {
        require(limitGiB in choicesGiB) { message("package_size_policy_invalid_package_size_limit", "Некорректный лимит размера пакета") }
        return if (limitGiB == 0) Long.MAX_VALUE else limitGiB.toLong() * 1024 * 1024 * 1024
    }
    /** Старый код сохранял 2 также при смене любой настройки; отдельного признака выбора не было. */
    fun restoredLimit(stored: Int?, hasSizePolicy: Boolean): Int = when {
        stored !in choicesGiB -> 0
        !hasSizePolicy && stored == 2 -> 0
        else -> stored ?: 0
    }
    fun copyBudget(limit: Long, available: Long): Long {
        require(limit > 0) { message("multi_apk_stager_the_size_limit_must_be_positive", "Лимит размера должен быть положительным") }
        requireSpace(1, available)
        return minOf(limit, available - PREPARATION_SPACE_RESERVE_BYTES)
    }
    fun requireSpace(required: Long, available: Long) {
        require(required >= 0) { message("package_size_policy_size_cannot_be_negative", "Размер не может быть отрицательным") }
        if (available < PREPARATION_SPACE_RESERVE_BYTES || required > available - PREPARATION_SPACE_RESERVE_BYTES) {
            throw InsufficientStorageException(required, available)
        }
    }
}
