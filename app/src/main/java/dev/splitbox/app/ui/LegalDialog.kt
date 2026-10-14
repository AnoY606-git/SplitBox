// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class LegalDocument(val title: String, val asset: String) {
    LICENSE("GNU General Public License v3", "licenses/GPL-3.0.txt"),
    ATTRIBUTION("Авторство и лицензия SplitBox", "NOTICE"),
    NOTICES("Лицензии компонентов", "THIRD_PARTY_NOTICES.md"),
}

@Composable
internal fun LegalDialog(document: LegalDocument, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text by produceState("Загрузка…", document) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(document.asset).bufferedReader().use { it.readText() } }
                .getOrElse { "Не удалось прочитать текст лицензии. Файл ${document.asset} доступен в исходном проекте." }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(document.title) },
        text = {
            SelectionContainer {
                Text(text, Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
