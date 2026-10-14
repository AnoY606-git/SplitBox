// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.installer

import java.io.IOException

/** Commit мог завершиться, но подтверждение Package Manager не получено. */
class UncertainInstallException(val packageName: String, message: String) : IOException(message)

/** Только полный последний ответ PM с единственным итоговым маркером доказывает отказ. */
internal val PmResult.provenCommitFailure: Boolean
    get() {
        if (success) return false
        val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val terminal = Regex("""Failure\s+\[([^\[\]\r\n]+)]""").matchEntire(lines.lastOrNull() ?: return false)
            ?: return false
        if (terminal.groupValues[1].isBlank()) return false
        // Оборванные ответы, несколько итогов и Success вместе с Failure оставляют результат UNKNOWN.
        return lines.count { Regex("""^(?:Success|Failure)(?:$|:|\s|\[)""").containsMatchIn(it) } == 1
    }

internal fun PmResult.requireCommitSuccess(packageName: String): PmResult {
    if (success) return this
    if (provenCommitFailure) return requireSuccess()
    throw UncertainInstallException(packageName,
        "Package Manager не подтвердил результат установки. Результат неизвестен; проверьте приложение.")
}
