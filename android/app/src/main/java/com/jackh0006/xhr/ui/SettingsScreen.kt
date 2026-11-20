package com.jackh0006.xhr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jackh0006.xhr.DailyUsage
import com.jackh0006.xhr.Format
import com.jackh0006.xhr.XhrConfig
import com.jackh0006.xhr.XhrState

@Composable
fun SettingsScreen(
    config: XhrConfig,
    onSave: (XhrConfig) -> Unit,
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
    onResetProfile: () -> Unit,
) {
    val context = LocalContext.current
    var ip by remember { mutableStateOf(config.googleIp) }
    var front by remember { mutableStateOf(config.frontDomain) }
    var port by remember { mutableStateOf(config.port.toString()) }
    var mtu by remember { mutableStateOf(config.mtu) }
    var limit by remember { mutableStateOf(config.dailyLimitMb.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val daily by DailyUsage.flow.collectAsState()

    Column(
        Modifier.fillMaxSize().statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text("Settings", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Tune the transport for your network",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        XhrCard {
            Text("Tunnel", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(9.dp))
            OutlinedTextField(
                value = ip,
                onValueChange = { ip = it.trim() },
                label = { Text("Google edge IPv4") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = front,
                onValueChange = { front = it.trim() },
                label = { Text("Front domain / SNI") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                label = { Text("Local SOCKS5 port") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.height(11.dp))
            Text("MTU", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1200, 1400, 1500).forEach { value ->
                    OutlinedButton(
                        onClick = { mtu = value },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (mtu == value) XhrColors.Blue.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Text("$value")
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(
                "1500 is standard. Use 1400 or 1200 if some apps time out on a mobile network.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))
        XhrCard {
            Text("Daily safety limit", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Optional. XHR stops the tunnel when today's combined upload and " +
                    "download reaches this size. 0 means no size limit.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(9.dp))
            OutlinedTextField(
                value = limit,
                onValueChange = { limit = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text("Limit in MB · 0 = off") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Used today: ${Format.bytes(daily.total)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(5.dp))
            OutlinedButton(
                onClick = { DailyUsage.reset(context, config.dailyLimitMb) },
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Rounded.RestartAlt, null)
                Spacer(Modifier.width(5.dp))
                Text("Reset today's counters")
            }
        }

        Spacer(Modifier.height(12.dp))
        XhrCard {
            Text("Profile", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Deployment, AUTH_KEY, and tunnel key are encrypted with Android Keystore.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(9.dp))
            OutlinedButton(onClick = onEditProfile, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Rounded.Edit, null)
                Spacer(Modifier.width(5.dp))
                Text("Edit deployment and keys")
            }
        }

        Spacer(Modifier.height(12.dp))
        XhrCard {
            Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            StatLine("Engine", if (XhrState.flow.value.running) "running" else "idle", XhrColors.Green)
            StatLine("Protocol", "XHR v1 · AES-256-GCM", XhrColors.Blue)
            StatLine(
                "Deployments",
                "${config.activeDeploymentCount()} of ${config.deploymentUrls().size} active",
                XhrColors.Blue,
            )
            StatLine("UDP / QUIC", "not supported", XhrColors.Red)
            StatLine("Apps Script calls today", Format.number(daily.calls), XhrColors.Yellow)
        }

        Spacer(Modifier.height(12.dp))
        XhrCard(color = XhrColors.Red.copy(alpha = 0.06f)) {
            Text("Danger zone", style = MaterialTheme.typography.titleMedium, color = XhrColors.Red)
            Spacer(Modifier.height(4.dp))
            Text(
                "Reset removes the profile and all keys from this phone. It does not change " +
                    "your Apps Script or exit server.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(9.dp))
            OutlinedButton(
                onClick = { confirmReset = true },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = XhrColors.Red),
            ) {
                Icon(Icons.Rounded.DeleteForever, null)
                Spacer(Modifier.width(5.dp))
                Text("Reset profile")
            }
        }

        error?.let {
            Spacer(Modifier.height(10.dp))
            InfoBanner("Cannot save", it, XhrColors.Red)
        }

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = {
                val next = config.copy(
                    googleIp = ip,
                    frontDomain = front,
                    port = port.toIntOrNull() ?: 0,
                    mtu = mtu,
                    dailyLimitMb = limit.toIntOrNull() ?: 0,
                )
                val invalid = next.validationError()
                if (invalid != null) error = invalid
                else {
                    error = null
                    onSave(next)
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(15.dp),
        ) {
            Text("Save settings")
        }
        Spacer(Modifier.height(100.dp))
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset XHR?") },
            text = { Text("This deletes the saved profile, keys, and daily counters on this phone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        onResetProfile()
                    }
                ) { Text("Reset", color = XhrColors.Red) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
            },
        )
    }
}
