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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jackh0006.xhr.Engine

private data class Donation(val asset: String, val network: String, val address: String)

private val donations = listOf(
    Donation("Bitcoin", "BTC", "bc1q8t0fn2yrsy4lh3m0pz34uj27t8vxjeavkjym83"),
    Donation("Dogecoin", "DOGE", "D6ZdMQ7mHGGmuH9prpZ2zjpnG5Q3WVRDtC"),
    Donation("Ethereum", "ERC-20", "0xdad428900a4359be8f76b3062df34211582e09eb"),
    Donation("USDT", "ERC-20", "0xdad428900a4359be8f76b3062df34211582e09eb"),
    Donation("BNB", "BEP-20", "0xdad428900a4359be8f76b3062df34211582e09eb"),
    Donation("USDT", "BEP-20", "0xdad428900a4359be8f76b3062df34211582e09eb"),
    Donation("TRON", "TRX", "TMpb6RNTuGNM1eTakm9kjds1mRTPYYJesf"),
    Donation("USDT", "TRC-20", "TMpb6RNTuGNM1eTakm9kjds1mRTPYYJesf"),
    Donation("Solana", "SOL", "BDCCrRez1yD1RpkAtiqKKDk3BfxPD8P7nkL26jCYrzgL"),
    Donation("USDT", "SPL", "BDCCrRez1yD1RpkAtiqKKDk3BfxPD8P7nkL26jCYrzgL"),
    Donation("USDC", "SPL", "BDCCrRez1yD1RpkAtiqKKDk3BfxPD8P7nkL26jCYrzgL"),
    Donation("XRP", "XRP Ledger", "rNUAhaATFLvosdu9m9M95bupRBtZ8eqpj9"),
    Donation("Toncoin", "TON", "UQCu6-3yGyQ5dzvcCxr2gobuvx5ddbS9EC690qtey92P5_wX"),
    Donation("Litecoin", "LTC", "ltc1q2gs89cfy3mumr7gu9w0zl9rllf80q67m5rmma8"),
)

@Composable
fun AboutScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val version = Engine.version().ifBlank { "1.0.0" }

    LazyColumn(
        modifier = Modifier.statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 18.dp, end = 18.dp, top = 12.dp, bottom = 110.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("About", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "XHR · $version · Android arm64",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onOpenSettings) {
                    Icon(Icons.Rounded.Settings, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Settings")
                }
            }
        }

        item {
            GradientCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LogoBadge(54.dp)
                    Spacer(Modifier.width(13.dp))
                    Column {
                        Text("XHR", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "Encrypted TCP relay for Google Apps Script",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("MIT", XhrColors.Blue)
                    Pill("No telemetry", XhrColors.Green)
                    Pill("TCP only", XhrColors.Red)
                }
            }
        }

        item {
            XhrCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Verified, null, tint = XhrColors.Blue)
                    Spacer(Modifier.width(8.dp))
                    Text("Built by", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "jackh0006",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "jackh109867@gmail.com",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "XHR is an independent project. It is not affiliated with Google, " +
                        "Cloudflare, MasterHttpRelayVPN, mhrv, XRV, or GooseRelayVPN.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            XhrCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, null, tint = XhrColors.Green)
                    Spacer(Modifier.width(8.dp))
                    Text("Security model", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                SecurityLine("AES-256-GCM end to end", "The tunnel key never reaches Google.")
                SecurityLine("HMAC-SHA256", "Client and exit authenticate each request; replay is rejected.")
                SecurityLine("Chrome TLS fingerprint", "uTLS Chrome profile over HTTP/2.")
                SecurityLine("Loopback only", "The local SOCKS5 listener is bound to 127.0.0.1.")
                SecurityLine("No accounts, no analytics", "Only the relay you configure is contacted.")
                Spacer(Modifier.height(7.dp))
                Text(
                    "Google still sees that a relay exists, its timing, size, and the exit URL. " +
                        "The exit sees destinations. XHR is censorship resistance, not anonymity.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionTitle("Donate")
        }
        item {
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = XhrColors.Yellow.copy(alpha = 0.10f),
            ) {
                Column(Modifier.padding(15.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Favorite, null, tint = XhrColors.Yellow)
                        Spacer(Modifier.width(8.dp))
                        Text("Keep XHR maintained", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "XHR is free and MIT licensed. Donations pay for test infrastructure, " +
                            "release signing, and maintenance. Only send the matching asset on the " +
                            "matching network.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        donations.forEach { donation ->
            item(key = donation.asset + donation.network) {
                DonationRow(donation)
            }
        }

        item {
            XhrCard {
                Text("Project", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                LinkRow("Repository", "https://github.com/jackh0006/XHR") {
                    openUrl(context, "https://github.com/jackh0006/XHR")
                }
                LinkRow("Google setup guide", "0 to 100, click by click") {
                    openUrl(context, "https://github.com/jackh0006/XHR/blob/main/docs/GUIDE-GOOGLE.md")
                }
                LinkRow("Cloudflare setup guide", "Google + Cloudflare exit") {
                    openUrl(context, "https://github.com/jackh0006/XHR/blob/main/docs/GUIDE-CLOUDFLARE.md")
                }
                LinkRow("Protocol specification", "Frames, HMAC, sessions") {
                    openUrl(context, "https://github.com/jackh0006/XHR/blob/main/docs/PROTOCOL.md")
                }
                LinkRow("Security policy", "Threat model and reporting") {
                    openUrl(context, "https://github.com/jackh0006/XHR/blob/main/SECURITY.md")
                }
                LinkRow("Latest release", "APK, changelog, checksums") {
                    openUrl(context, "https://github.com/jackh0006/XHR/releases/latest")
                }
            }
        }

        item {
            Text(
                "MIT © 2026 jackh0006. This app is provided as-is. You are responsible for " +
                    "the laws and terms of service that apply to you.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SecurityLine(title: String, body: String) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Rounded.CheckCircle, null,
            tint = XhrColors.Green,
            modifier = Modifier.size(16.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(7.dp))
        Column {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(
                body,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DonationRow(donation: Donation) {
    val context = LocalContext.current
    Surface(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .clickable { copy(context, "${donation.asset} ${donation.network}", donation.address) },
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = XhrColors.Blue.copy(alpha = 0.10f),
            ) {
                Text(
                    donation.asset.take(4).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = XhrColors.Blue,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${donation.asset} · ${donation.network}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    donation.address,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Icon(
                Icons.Rounded.ContentCopy, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Rounded.OpenInNew, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}
