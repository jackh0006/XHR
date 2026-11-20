package com.jackh0006.xhr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jackh0006.xhr.EdgeProber
import com.jackh0006.xhr.EdgeReport
import com.jackh0006.xhr.EdgeResult
import com.jackh0006.xhr.XhrConfig

@Composable
fun EdgeScreen(
    config: XhrConfig,
    onUseIp: (String) -> Unit,
) {
    var candidates by remember {
        mutableStateOf(
            (listOf(config.googleIp) + EdgeProber.defaultCandidates())
                .distinct().joinToString("\n")
        )
    }
    var sni by remember { mutableStateOf(config.frontDomain.ifBlank { "www.google.com" }) }
    var testing by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<EdgeReport?>(null) }

    fun runTest() {
        testing = true
        report = null
        EdgeProber.run(EdgeProber.parseCandidates(candidates), sni.ifBlank { "www.google.com" }) {
            testing = false
            report = it
        }
    }

    LazyColumn(
        modifier = Modifier.statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 18.dp, end = 18.dp, top = 12.dp, bottom = 110.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        item {
            Column {
                Text("Edge lab", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Find the fastest Google IP for your mobile network.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            GradientCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Bolt, null, tint = XhrColors.Yellow)
                    Spacer(Modifier.width(8.dp))
                    Text("Current edge", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    config.googleIp,
                    style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                )
                Text(
                    "SNI ${config.frontDomain} · TCP 443 · Chrome fingerprint",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            XhrCard {
                Text("Test candidates", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = candidates,
                    onValueChange = { candidates = it },
                    label = { Text("Google IPv4 addresses · one per line") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = sni,
                    onValueChange = { sni = it.trim() },
                    label = { Text("SNI / front domain") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    trailingIcon = {
                        Icon(Icons.Rounded.Language, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                )
                Spacer(Modifier.height(11.dp))
                Button(
                    onClick = { runTest() },
                    enabled = !testing,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(15.dp),
                ) {
                    if (testing) {
                        CircularProgressIndicator(
                            Modifier.size(17.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Testing TLS handshakes…")
                    } else {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (report == null) "Test all IPs" else "Test again")
                    }
                }
                Text(
                    "This opens a real TLS 1.2+/HTTP2 handshake to each IP. It does not " +
                        "consume Apps Script quota and does not start the tunnel.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 7.dp),
                )
            }
        }

        report?.best?.let { best ->
            item {
                InfoBanner(
                    "Best result · ${best.ip}",
                    "${best.ms} ms · ${best.cn.ifBlank { sni }} · ${best.alpn.ifBlank { "TLS" }}",
                    XhrColors.Green,
                    icon = { Icon(Icons.Rounded.CheckCircle, null, tint = XhrColors.Green) },
                )
                Spacer(Modifier.height(9.dp))
                Button(
                    onClick = { onUseIp(best.ip) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(15.dp),
                ) {
                    Text("Use ${best.ip}")
                }
            }
        }

        report?.let { r ->
            if (r.best == null) {
                item {
                    InfoBanner(
                        "No usable edge",
                        r.error.ifBlank { "All candidates failed. Try another network or IP list." },
                        XhrColors.Red,
                        icon = { Icon(Icons.Rounded.ErrorOutline, null, tint = XhrColors.Red) },
                    )
                }
            }
            item {
                SectionTitle("Results · ${r.results.size}")
            }
            items(r.results, key = { it.ip }) { result ->
                EdgeRow(result, selected = result.ip == config.googleIp) { onUseIp(result.ip) }
            }
        }

        item {
            XhrCard(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)) {
                Text("How to read this", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "A low number is a good network path to Google, not a promise of relay " +
                        "speed. Apps Script adds its own round trip. If every IP fails, your " +
                        "network may block Google edge IPs or the SNI; try Wi-Fi or another " +
                        "front domain.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EdgeRow(result: EdgeResult, selected: Boolean, onClick: () -> Unit) {
    val accent = if (result.ok) XhrColors.Green else XhrColors.Red
    Column(
        Modifier.fillMaxWidth()
            .background(
                if (selected) XhrColors.Blue.copy(alpha = 0.08f)
                else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (result.ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                result.ip,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f),
            )
            Text(
                if (result.ok) "${result.ms} ms" else "fail",
                style = MaterialTheme.typography.labelLarge,
                color = accent,
            )
        }
        if (result.ok) {
            val detail = listOf(result.cn, result.issuer, result.alpn)
                .filter { it.isNotBlank() }.joinToString(" · ")
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 26.dp, top = 2.dp),
                )
            }
        } else if (result.error.isNotBlank()) {
            Text(
                result.error,
                style = MaterialTheme.typography.labelSmall,
                color = XhrColors.Red,
                modifier = Modifier.padding(start = 26.dp, top = 2.dp),
            )
        }
    }
}
