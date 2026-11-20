package com.jackh0006.xhr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jackh0006.xhr.ConnMode
import com.jackh0006.xhr.LogLine
import com.jackh0006.xhr.XhrLog
import com.jackh0006.xhr.XhrSnapshot

@Composable
fun LogsScreen(snapshot: XhrSnapshot) {
    val context = LocalContext.current
    val lines by XhrLog.flow.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Live logs", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Only on this device · never uploaded",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Pill(
                if (snapshot.running) "LIVE" else "IDLE",
                if (snapshot.running) XhrColors.Green else MaterialTheme.colorScheme.onSurfaceVariant,
                filled = snapshot.running,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            OutlinedButton(
                onClick = {
                    val text = lines.joinToString("\n") { "${it.time} ${it.text}" }
                    copy(context, "XHR logs", text)
                },
                enabled = lines.isNotEmpty(),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(13.dp),
            ) {
                Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Copy all")
            }
            OutlinedButton(
                onClick = { XhrLog.clear() },
                enabled = lines.isNotEmpty(),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(13.dp),
            ) {
                Icon(Icons.Rounded.DeleteSweep, null, Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Clear")
            }
        }
        Spacer(Modifier.height(10.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            if (lines.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Rounded.Terminal, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(30.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Start the tunnel to see live events.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    itemsIndexed(lines) { _, line ->
                        LogRow(line, snapshot.mode)
                    }
                }
            }
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun LogRow(line: LogLine, mode: ConnMode) {
    val color = when {
        line.text.contains("error", true) || line.text.contains("failed", true) -> XhrColors.Red
        line.text.contains("CONNECT", true) -> XhrColors.Blue
        line.text.contains("test", true) -> XhrColors.Green
        line.text.contains("session", true) -> XhrColors.Yellow
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth()) {
        Text(
            line.time,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(7.dp))
        Text(
            line.text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            ),
            color = if (color == MaterialTheme.colorScheme.onSurfaceVariant) {
                if (mode == ConnMode.TUN) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface
            } else color,
        )
    }
}
