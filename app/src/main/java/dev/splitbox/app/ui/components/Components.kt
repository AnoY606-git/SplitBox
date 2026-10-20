// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.ui.components

import dev.splitbox.core.message
import dev.splitbox.core.nestedMessage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.splitbox.app.ui.LocalizedIcon as Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import dev.splitbox.app.ui.LocalizedText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.splitbox.app.model.BackendMode
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.4.sp)
}

@Composable
fun SectionHeading(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun StatusPill(text: String, icon: ImageVector, modifier: Modifier = Modifier, error: Boolean = false) {
    val foreground = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.primary
    val background = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
    Row(modifier.background(background, RoundedCornerShape(100.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Icon(icon, null, Modifier.size(16.dp), tint = foreground)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = foreground)
    }
}

/** Оригинальный геометрический знак: три раздельные грани образуют открытую коробку. */
@Composable
fun SplitBoxMark(modifier: Modifier = Modifier, expanded: Boolean = false) {
    val primary = MaterialTheme.colorScheme.primary
    val second = MaterialTheme.colorScheme.tertiary
    val fill = MaterialTheme.colorScheme.primaryContainer
    Canvas(modifier.clearAndSetSemantics { }) {
        val w = size.width
        val h = size.height
        fun polygon(points: List<Offset>, color: Color, outline: Boolean = false) {
            val path = Path().apply {
                moveTo(points.first().x * w, points.first().y * h)
                points.drop(1).forEach { lineTo(it.x * w, it.y * h) }
                close()
            }
            drawPath(path, color)
            if (outline) drawPath(path, primary.copy(alpha = 0.5f), style = Stroke(width = w * 0.012f))
        }
        if (expanded) {
            drawCircle(primary.copy(alpha = 0.06f), radius = w * 0.47f, center = Offset(w * 0.5f, h * 0.5f))
            drawCircle(primary.copy(alpha = 0.14f), radius = w * 0.43f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(w * 0.008f))
            drawCircle(second.copy(alpha = 0.7f), radius = w * 0.024f, center = Offset(w * 0.88f, h * 0.28f))
            drawCircle(primary.copy(alpha = 0.6f), radius = w * 0.017f, center = Offset(w * 0.14f, h * 0.72f))
        }
        polygon(listOf(Offset(.2f,.33f), Offset(.5f,.16f), Offset(.8f,.33f), Offset(.5f,.51f)), primary)
        polygon(listOf(Offset(.2f,.4f), Offset(.46f,.55f), Offset(.46f,.86f), Offset(.2f,.7f)), fill, true)
        polygon(listOf(Offset(.54f,.55f), Offset(.8f,.4f), Offset(.8f,.7f), Offset(.54f,.86f)), second)
    }
}

fun BackendMode.displayName(): String = when (this) {
    BackendMode.SYSTEM -> message("history_presentation_system", "Системный")
    BackendMode.ROOT -> "Root"
    BackendMode.SHIZUKU -> "Shizuku"
}

fun formatBytes(value: Long): String {
    val number = value.coerceAtLeast(0)
    return when {
        number >= 1024L * 1024 * 1024 -> message("components_2f_gb", "%.2f ГБ", number / (1024.0 * 1024 * 1024))
        number >= 1024L * 1024 -> message("components_1f_mb", "%.1f МБ", number / (1024.0 * 1024))
        number >= 1024 -> message("components_1f_kb", "%.1f КБ", number / 1024.0)
        else -> message("components_1_b", "%1\$s Б", number)
    }
}

fun formatTimestamp(timestamp: Long, locale: Locale = Locale.getDefault()): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(timestamp))
