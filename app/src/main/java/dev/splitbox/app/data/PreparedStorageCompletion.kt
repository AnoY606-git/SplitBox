// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import dev.splitbox.app.model.OperationResult
import dev.splitbox.core.PreparedPackage
import java.io.File

/** Сохраняет возможность экспорта OBB после перезапуска; указатель подтверждается до очистки. */
internal suspend fun finishPreparedStorage(result: OperationResult, prepared: PreparedPackage?,
    fallbackDirectory: File?, cleanAfter: Boolean, savePrepared: (String) -> Unit,
    clearPrepared: () -> Unit, flush: suspend () -> Unit, deleteDirectory: (File) -> Unit): Boolean {
    val keepObb = result.success && result.obbWarning != null && prepared?.obbFiles?.isNotEmpty() == true
    if (keepObb) savePrepared(prepared!!.workDir.absolutePath) else clearPrepared()
    flush()
    if (cleanAfter && !keepObb) (prepared?.workDir ?: fallbackDirectory)?.let(deleteDirectory)
    return keepObb
}
