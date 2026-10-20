// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.ui

import dev.splitbox.app.R
import androidx.compose.ui.res.stringResource
import dev.splitbox.core.message
import dev.splitbox.core.nestedMessage

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import dev.splitbox.app.ui.LocalizedText as Text
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
    ATTRIBUTION(message("legal_dialog_splitbox_attribution_and_license", "Авторство и лицензия SplitBox"), "NOTICE"),
    NOTICES(message("legal_dialog_component_licenses", "Лицензии компонентов"), "THIRD_PARTY_NOTICES.md"),
}

@Composable
internal fun LegalDialog(document: LegalDocument, onDismiss: () -> Unit) {

    val context = LocalContext.current
    val language = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language
    val asset = if (document != LegalDocument.LICENSE && language != "ru") document.asset + ".en" else document.asset
    val text by produceState(stringResource(R.string.legal_dialog_loading), document, asset) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(asset).bufferedReader().use { it.readText() } }
                .getOrElse { message("legal_dialog_could_not_read_the_license_text_the", "Не удалось прочитать текст лицензии. Файл %1\$s доступен в исходном проекте.", document.asset) }
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
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.history_screen_close)) } },
    )
}
