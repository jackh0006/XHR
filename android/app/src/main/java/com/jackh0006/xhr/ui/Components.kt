package com.jackh0006.xhr.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

@Composable
fun XhrLogo(size: Dp = 46.dp, center: Color = Color.White) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = s * 0.105f
        val gap = s * 0.045f
        val cx = s / 2f
        val cy = s / 2f
        val arm = s * 0.27f
        drawLine(XhrColors.Blue, Offset(arm, arm), Offset(cx - gap, cy - gap), stroke, StrokeCap.Round)
        drawLine(XhrColors.Red, Offset(s - arm, arm), Offset(cx + gap, cy - gap), stroke, StrokeCap.Round)
        drawLine(XhrColors.Green, Offset(arm, s - arm), Offset(cx - gap, cy + gap), stroke, StrokeCap.Round)
        drawLine(XhrColors.Yellow, Offset(s - arm, s - arm), Offset(cx + gap, cy + gap), stroke, StrokeCap.Round)
        drawCircle(center, s * 0.054f)
    }
}

@Composable
fun LogoBadge(size: Dp = 44.dp) {
    Surface(
        shape = RoundedCornerShape(size * 0.28f),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 1.dp,
    ) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            XhrLogo(size * 0.72f, MaterialTheme.colorScheme.surface)
        }
    }
}

@Composable
fun XhrCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = color),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

@Composable
fun GradientCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors = if (dark) {
        listOf(Color(0xFF162340), Color(0xFF10182B))
    } else {
        listOf(Color(0xFFFFFFFF), Color(0xFFEAF2FF))
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (dark) Color(0xFF26375A) else Color(0xFFDCE7FA),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(Modifier.background(Brush.linearGradient(colors))) {
            Column(Modifier.padding(20.dp), content = content)
        }
    }
}

private fun Color.luminance(): Float =
    0.2126f * red + 0.7152f * green + 0.0722f * blue

@Composable
fun SectionTitle(text: String, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
fun Pill(text: String, color: Color, filled: Boolean = false) {
    val bg = if (filled) color else color.copy(alpha = 0.12f)
    val fg = if (filled) Color.White else color
    Surface(shape = CircleShape, color = bg) {
        Text(
            text,
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun MetricTile(
    label: String,
    value: String,
    sub: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape).background(accent)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(7.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                sub,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun TrafficChart(
    down: List<Float>,
    up: List<Float>,
    modifier: Modifier = Modifier,
) {
    val line = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val blue = XhrColors.Blue
    val green = XhrColors.Green
    Canvas(modifier.fillMaxWidth().height(92.dp)) {
        val w = size.width
        val h = size.height
        for (i in 1..3) {
            val y = h * i / 4f
            drawLine(line, Offset(0f, y), Offset(w, y), 1f)
        }
        fun path(values: List<Float>, color: Color) {
            if (values.size < 2) return
            val maxValue = max(values.maxOrNull() ?: 0f, 1f)
            val step = w / (values.size - 1).coerceAtLeast(1)
            var prev = Offset(0f, h - (values[0] / maxValue) * (h - 6f) - 3f)
            for (i in 1 until values.size) {
                val p = Offset(step * i, h - (values[i] / maxValue) * (h - 6f) - 3f)
                drawLine(color, prev, p, 2.6f, StrokeCap.Round)
                prev = p
            }
        }
        path(up, green)
        path(down, blue)
    }
}

@Composable
fun StatLine(label: String, value: String, accent: Color? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (accent != null) {
            Dot(accent, 7.dp)
            Spacer(Modifier.width(9.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun CopyField(label: String, value: String, mono: Boolean = true) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif,
                    fontSize = 12.sp,
                ),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { copy(context, label, value) }) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Copy")
            }
        }
    }
}

fun copy(context: Context, label: String, value: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
}

@Composable
fun InfoBanner(
    title: String,
    body: String,
    accent: Color = XhrColors.Blue,
    icon: @Composable (() -> Unit)? = null,
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = accent.copy(alpha = 0.09f),
    ) {
        Row(Modifier.padding(14.dp)) {
            if (icon != null) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
                Spacer(Modifier.width(10.dp))
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = accent)
                Spacer(Modifier.height(3.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun ThinDivider() {
    HorizontalDivider(
        Modifier.padding(vertical = 10.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
    )
}

@Composable
fun RowScope.Segment(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.weight(1f)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primary
        else Color.Transparent,
    ) {
        Text(
            text,
            modifier = Modifier.padding(vertical = 11.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
