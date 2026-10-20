// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.ui

import android.content.Context
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import dev.splitbox.core.MessageEnvelope

/** Разрешаются лишь ключи собственного каталога. Внешние аргументы не переводятся. */
fun Context.resolveMessage(value: String): String = MessageEnvelope.resolve(value) { key, arguments ->
    messageResource(key)?.let { resource ->
        runCatching { getString(resource, *arguments.toTypedArray()) }.getOrNull()
    }
}

@Composable
internal fun localizedText(value: String): String {
    // Чтение Configuration обеспечивает повторное разрешение активного состояния при смене языка.
    LocalConfiguration.current
    return LocalContext.current.resolveMessage(value)
}

@Composable
internal fun LocalizedText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) = androidx.compose.material3.Text(
    localizedText(text), modifier, color, fontSize, fontStyle, fontWeight, fontFamily,
    letterSpacing, textDecoration, textAlign, lineHeight, overflow, softWrap, maxLines,
    minLines, onTextLayout, style,
)

@Composable
internal fun LocalizedIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) = androidx.compose.material3.Icon(imageVector, contentDescription?.let { localizedText(it) }, modifier, tint)
