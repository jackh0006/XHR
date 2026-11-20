package com.jackh0006.xhr.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jackh0006.xhr.ConnMode
import com.jackh0006.xhr.ConnectionTester
import com.jackh0006.xhr.DailySnapshot
import com.jackh0006.xhr.Format
import com.jackh0006.xhr.ProxyTestResult
import com.jackh0006.xhr.XhrConfig
import com.jackh0006.xhr.XhrSnapshot
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun HomeScreen(
    config: XhrConfig,
    snapshot: XhrSnapshot,
    daily: DailySnapshot,
    onSettings: () -> Unit,
    onModeChange: (ConnMode) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenEdge: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val downHistory = remember { mutableStateListOf<Float>() }
    val upHistory = remember { mutableStateListOf<Float>() }
    var elapsed by remember { mutableStateOf("00:00") }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ProxyTestResult?>(null) }

    LaunchedEffect(snapshot.upRate, snapshot.downRate) {
        upHistory.add(snapshot.upRate.toFloat())
        downHistory.add(snapshot.downRate.toFloat())
        while (upHistory.size > 64) upHistory.removeAt(0)
        while (downHistory.size > 64) downHistory.removeAt(0)
    }
    LaunchedEffect(snapshot.running, snapshot.startedAt) {
        while (snapshot.running && snapshot.startedAt > 0) {
            elapsed = Format.duration(System.currentTimeMillis() - snapshot.startedAt)
            delay(1000)
        }
        if (!snapshot.running) elapsed = "00:00"
    }

    val connected = snapshot.running
    val busy = snapshot.starting
    val progress = (daily.calls.toFloat() / daily.callsLimit.toFloat()).coerceIn(0f, 1f)

    LazyColumn(
        modifier = Modifier.statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 18.dp, end = 18.dp, top = 10.dp, bottom = 110.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoBadge(44.dp)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("XHR", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Encrypted TCP relay",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(
                    when {
                        snapshot.phase == "error" -> "ERROR" to XhrColors.Red
                        snapshot.starting -> "CONNECTING" to XhrColors.Yellow
                        snapshot.running -> "CONNECTED" to XhrColors.Green
                        else -> "STANDBY" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onSettings) {
                    Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                }
            }
        }

        item {
            GradientCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (connected) "Your tunnel is live" else "Ready when you are",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when {
                                snapshot.starting -> "Starting the encrypted session…"
                                connected && snapshot.mode == ConnMode.TUN ->
                                    "All TCP apps · $elapsed · ${snapshot.active} active"
                                connected -> "SOCKS5 127.0.0.1:${snapshot.port} · $elapsed"
                                else -> "TCP only · AES-256-GCM · Chrome TLS"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (connected) {
                        Pill("LIVE", XhrColors.Green, filled = true)
                    }
                }
                Spacer(Modifier.height(20.dp))

                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .padding(4.dp),
                ) {
                    Segment(
                        "TUN · all apps",
                        selected = config.mode == ConnMode.TUN,
                        enabled = !connected && !busy,
                    ) { onModeChange(ConnMode.TUN) }
                    Segment(
                        "SOCKS5 · v2rayNG",
                        selected = config.mode == ConnMode.PROXY,
                        enabled = !connected && !busy,
                    ) { onModeChange(ConnMode.PROXY) }
                }

                Spacer(Modifier.height(22.dp))
                PowerButton(
                    connected = connected,
                    starting = busy,
                    mode = snapshot.mode,
                    onClick = { if (connected) onStop() else onStart() },
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        busy -> "Establishing a secure session"
                        connected -> "Tap to disconnect"
                        config.mode == ConnMode.TUN -> "Tap to protect all TCP apps"
                        else -> "Tap to start the local SOCKS5 proxy"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        snapshot.error?.let { message ->
            item {
                InfoBanner(
                    "Connection problem",
                    message,
                    XhrColors.Red,
                    icon = { Icon(Icons.Rounded.ErrorOutline, null, tint = XhrColors.Red) },
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile(
                    "Upload", Format.bytes(snapshot.up), Format.rate(snapshot.upRate),
                    XhrColors.Green, Modifier.weight(1f),
                )
                MetricTile(
                    "Download", Format.bytes(snapshot.down), Format.rate(snapshot.downRate),
                    XhrColors.Blue, Modifier.weight(1f),
                )
            }
        }

        item {
            XhrCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Live throughput", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Dot(XhrColors.Blue, 7.dp)
                    Spacer(Modifier.width(5.dp))
                    Text("DOWN", style = MaterialTheme.typography.labelSmall, color = XhrColors.Blue)
                    Spacer(Modifier.width(10.dp))
                    Dot(XhrColors.Green, 7.dp)
                    Spacer(Modifier.width(5.dp))
                    Text("UP", style = MaterialTheme.typography.labelSmall, color = XhrColors.Green)
                }
                Spacer(Modifier.height(8.dp))
                TrafficChart(down = downHistory, up = upHistory)
                Spacer(Modifier.height(4.dp))
                Row {
                    Text(
                        "${Format.rate(snapshot.downRate)} down · ${Format.rate(snapshot.upRate)} up",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${snapshot.active} streams",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            XhrCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Today", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        daily.date.ifBlank { "—" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        Format.number(daily.calls),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "/ 20,000 relay requests",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Spacer(Modifier.height(7.dp))
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape),
                    color = if (progress > 0.85f) XhrColors.Red else XhrColors.Blue,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row {
                    Text(
                        "Upload ${Format.bytes(daily.up)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        "Download ${Format.bytes(daily.down)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${config.activeDeploymentCount()}/${config.deploymentUrls().size} deployments",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (daily.limitBytes > 0) {
                    Spacer(Modifier.height(10.dp))
                    val used = (daily.total.toFloat() / daily.limitBytes.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = used,
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = if (used > 0.9f) XhrColors.Red else XhrColors.Green,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${Format.bytes(daily.total)} of ${Format.bytes(daily.limitBytes)} daily limit",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Counts locally. GAS free tier is 20,000 UrlFetch calls per day.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            XhrCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.NetworkCheck, null, tint = XhrColors.Blue)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Prove the path", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Fetch HTTPS through the relay and show the exit IP.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        testing = true
                        testResult = null
                        ConnectionTester.run(if (snapshot.port > 0) snapshot.port else config.port) {
                            testing = false
                            testResult = it
                        }
                    },
                    enabled = connected && !testing,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    if (testing) {
                        CircularProgressIndicator(
                            Modifier.size(17.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Testing the whole relay…")
                    } else {
                        Text(if (connected) "Test connection" else "Connect to run the test")
                    }
                }
                testResult?.let { r ->
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (r.ok) XhrColors.Green.copy(alpha = 0.1f)
                        else XhrColors.Red.copy(alpha = 0.1f),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (r.ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                                null,
                                tint = if (r.ok) XhrColors.Green else XhrColors.Red,
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    if (r.ok) "Relay works · ${r.ms} ms" else "Relay test failed",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (r.ok) XhrColors.Green else XhrColors.Red,
                                )
                                Text(
                                    if (r.ok) {
                                        if (r.exitIp.isNotBlank()) "Exit IP ${r.exitIp}"
                                        else "HTTPS request completed"
                                    } else r.error,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            XhrCard {
                Text("Quick actions", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onOpenEdge,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Rounded.Bolt, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Find best IP")
                    }
                    OutlinedButton(
                        onClick = onOpenLogs,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("Live logs")
                    }
                }
                if (connected && snapshot.mode == ConnMode.PROXY) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { copy(context, "SOCKS5 address", "127.0.0.1:${snapshot.port}") },
                        shape = RoundedCornerShape(14.dp),
                        color = XhrColors.Blue.copy(alpha = 0.09f),
                    ) {
                        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.ContentCopy, null, tint = XhrColors.Blue, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "v2rayNG outbound · SOCKS5 127.0.0.1:${snapshot.port}",
                                style = MaterialTheme.typography.labelLarge,
                                color = XhrColors.Blue,
                            )
                        }
                    }
                }
            }
        }

        if (config.mode == ConnMode.PROXY) {
            item {
                InfoBanner(
                    "Using v2rayNG?",
                    "Add a SOCKS5 outbound to 127.0.0.1:${config.port}. If v2rayNG runs " +
                        "as a VPN, exclude the XHR app in v2rayNG's per-app list so XHR's " +
                        "own Google connection cannot loop back into v2rayNG.",
                    XhrColors.Yellow,
                )
            }
        }

        item {
            XhrCard(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, null, tint = XhrColors.Green, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("What XHR protects", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    "Traffic is sealed with AES-256-GCM between this phone and the exit. " +
                        "Apps Script only forwards opaque frames. The ISP sees a Google IP, " +
                        "TCP 443, and a Chrome TLS fingerprint. Google sees metadata, not " +
                        "destinations or content. The exit sees the destination.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Pill("AES-256-GCM", XhrColors.Blue)
                    Pill("HMAC-SHA256", XhrColors.Green)
                    Pill("Chrome TLS", XhrColors.Yellow)
                    Pill("TCP only", XhrColors.Red)
                }
            }
        }
    }
}

@Composable
private fun StatusPill(value: Pair<String, Color>) {
    Pill(value.first, value.second, filled = value.first == "CONNECTED" || value.first == "ERROR")
}

@Composable
private fun PowerButton(
    connected: Boolean,
    starting: Boolean,
    mode: ConnMode,
    onClick: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "pulse-value",
    )
    val colors = when {
        starting -> listOf(XhrColors.Blue, XhrColors.BlueDark)
        connected -> listOf(XhrColors.Red, Color(0xFFB3261E))
        else -> listOf(XhrColors.Blue, XhrColors.BlueDark)
    }
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(116.dp)
                .scale(if (connected || starting) pulse else 1f)
                .clip(CircleShape)
                .background(colors[0].copy(alpha = 0.12f))
        )
        Box(
            Modifier.size(94.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(colors))
                .clickable(enabled = !starting, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (starting) {
                CircularProgressIndicator(
                    Modifier.size(30.dp),
                    strokeWidth = 3.dp,
                    color = Color.White,
                )
            } else {
                Icon(
                    Icons.Rounded.PowerSettingsNew,
                    contentDescription = if (connected) "Disconnect" else "Connect",
                    tint = Color.White,
                    modifier = Modifier.size(38.dp),
                )
            }
        }
    }
}
