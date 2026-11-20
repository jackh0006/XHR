package com.jackh0006.xhr.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.jackh0006.xhr.XhrConfig

@Composable
fun SetupScreen(
    initial: XhrConfig,
    onSave: (XhrConfig) -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var gasLines by remember {
        mutableStateOf(initial.deploymentUrls().joinToString("\n"))
    }
    var useCount by remember {
        val initialCount = initial.deploymentUrls().size
        mutableStateOf(
            if (initial.activeDeployments in 1..initialCount) initial.activeDeployments
            else initialCount.coerceAtLeast(1)
        )
    }
    val parsedUrls = remember(gasLines) {
        gasLines.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(5)
    }
    LaunchedEffect(parsedUrls.size) {
        if (useCount > parsedUrls.size) useCount = parsedUrls.size.coerceAtLeast(1)
    }
    var auth by remember { mutableStateOf(initial.authKey) }
    var e2e by remember { mutableStateOf(initial.e2eKey) }
    var ip by remember { mutableStateOf(initial.googleIp) }
    var front by remember { mutableStateOf(initial.frontDomain) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var advanced by remember { mutableStateOf(false) }
    var showAuth by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoBadge(48.dp)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("XHR", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Encrypted TCP relay for Google Apps Script",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(22.dp))

        GradientCard {
            Text("Finish setup", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "Add one to five Apps Script deployments. They share the same " +
                    "AUTH_KEY and tunnel key. Everything stays encrypted on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(17.dp), tint = XhrColors.Green)
                Spacer(Modifier.size(6.dp))
                Text(
                    "AES-256-GCM · Android Keystore",
                    style = MaterialTheme.typography.labelSmall,
                    color = XhrColors.Green,
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        InputCard(
            title = "1 · Deployments · 1 to 5",
            help = "Put one Deployment ID or /exec URL per line. All deployments must " +
                "use the same AUTH_KEY and exit. They share one Google quota.",
        ) {
            OutlinedTextField(
                value = gasLines,
                onValueChange = { gasLines = it },
                label = { Text("Deployment IDs or URLs · one per line") },
                minLines = 4,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                trailingIcon = {
                    PasteButton {
                        clipboard.getText()?.text?.let { gasLines = it.trim() }
                    }
                },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Active deployments: $useCount of ${parsedUrls.size}",
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                (1..5).forEach { n ->
                    FilterChip(
                        selected = useCount == n,
                        onClick = { useCount = n },
                        enabled = n <= parsedUrls.size,
                        label = { Text("$n") },
                    )
                }
            }
            Text(
                "Start with 2 active deployments. Use 1 if Apps Script quota is tight. " +
                    "Five only helps failover or separate Google accounts.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))
        InputCard(
            title = "2 · Client password",
            help = "The AUTH_KEY you set in Apps Script → Project Settings → Script Properties.",
        ) {
            OutlinedTextField(
                value = auth,
                onValueChange = { auth = it.trim() },
                label = { Text("AUTH_KEY") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showAuth) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showAuth = !showAuth }) {
                            Icon(
                                if (showAuth) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = "Show or hide password",
                            )
                        }
                        PasteButton { clipboard.getText()?.text?.let { auth = it.trim() } }
                    }
                },
            )
        }

        Spacer(Modifier.height(12.dp))
        InputCard(
            title = "3 · Tunnel key",
            help = "Exactly 64 hex characters (the E2E key). The server exits share it; " +
                "Apps Script never does.",
        ) {
            OutlinedTextField(
                value = e2e,
                onValueChange = { e2e = it.trim().lowercase() },
                label = { Text("E2E tunnel key · 64 hex") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                visualTransformation = if (showKey) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = "Show or hide key",
                            )
                        }
                        PasteButton { clipboard.getText()?.text?.let { e2e = it.trim().lowercase() } }
                    }
                },
            )
        }

        Spacer(Modifier.height(12.dp))
        InputCard(
            title = "4 · Google edge",
            help = "The IP the client dials with SNI www.google.com. You can find a faster " +
                "one in the Edge tab after setup.",
        ) {
            OutlinedTextField(
                value = ip,
                onValueChange = { ip = it.trim() },
                label = { Text("Google edge IPv4") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                trailingIcon = {
                    PasteButton { clipboard.getText()?.text?.let { ip = it.trim() } }
                },
            )
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { advanced = !advanced }) {
            Icon(
                if (advanced) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
            )
            Spacer(Modifier.size(4.dp))
            Text(if (advanced) "Hide advanced settings" else "Advanced settings")
        }
        AnimatedVisibility(advanced) {
            Column {
                OutlinedTextField(
                    value = front,
                    onValueChange = { front = it.trim() },
                    label = { Text("Front domain (SNI)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
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
                Spacer(Modifier.height(8.dp))
                Text(
                    "Use 1080 for both TUN and v2rayNG. The listener is bound to 127.0.0.1 only.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        error?.let {
            Spacer(Modifier.height(10.dp))
            InfoBanner("Check the profile", it, XhrColors.Red)
        }

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = {
                val urls = parsedUrls
                val active = if (urls.isEmpty()) 0 else useCount.coerceIn(1, urls.size)
                val cfg = initial.copy(
                    gasUrl = urls.firstOrNull() ?: "",
                    extraGasUrls = urls.drop(1).take(4),
                    activeDeployments = if (active == urls.size) 0 else active,
                    authKey = auth,
                    e2eKey = e2e,
                    googleIp = ip,
                    frontDomain = front,
                    port = port.toIntOrNull() ?: 0,
                )
                val invalid = cfg.validationError()
                if (invalid != null) {
                    error = invalid
                } else {
                    error = null
                    onSave(cfg)
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Text("Save profile", style = MaterialTheme.typography.labelLarge)
        }
        if (onCancel != null) {
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
        TextButton(
            onClick = {
                openUrl(context, "https://github.com/jackh0006/XHR/blob/main/docs/GUIDE-GOOGLE.md")
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.OpenInNew, null, Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text("Open the 0-to-100 setup guide")
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "XHR is MIT open source. Your keys are stored in this app's private storage. " +
                "There is no account, analytics, or telemetry.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun InputCard(
    title: String,
    help: String,
    content: @Composable () -> Unit,
) {
    XhrCard {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(3.dp))
        Text(
            help,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun PasteButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = ButtonDefaults.TextButtonContentPadding) {
        Text("Paste", style = MaterialTheme.typography.labelSmall)
    }
}

fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
