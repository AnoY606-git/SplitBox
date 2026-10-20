// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import android.annotation.SuppressLint
import android.content.SharedPreferences

/** KTX edit(commit = true) теряет Boolean; журналу и истории нужно подтверждение записи. */
@SuppressLint("UseKtx")
internal inline fun SharedPreferences.commitChecked(
    failureMessage: () -> String,
    changes: SharedPreferences.Editor.() -> Unit,
) {
    val editor = edit()
    editor.changes()
    check(editor.commit(), failureMessage)
}
