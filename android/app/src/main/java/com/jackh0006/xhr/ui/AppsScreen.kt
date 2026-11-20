package com.jackh0006.xhr.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.jackh0006.xhr.ConnMode
import com.jackh0006.xhr.SplitMode
import com.jackh0006.xhr.XhrConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AppInfo(
    val packageName: String,
    val label: String,
    val system: Boolean,
    val icon: ImageBitmap?,
)

@Composable
fun AppsScreen(
    config: XhrConfig,
    onChange: (XhrConfig) -> Unit,
) {
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    val apps by produceState<List<AppInfo>>(emptyList(), config.showSystemApps) {
        value = withContext(Dispatchers.IO) { loadApps(context, config.showSystemApps) }
    }
    val filtered = remember(apps, search) {
        if (search.isBlank()) apps
        else apps.filter {
            it.label.contains(search, ignoreCase = true) ||
                it.packageName.contains(search, ignoreCase = true)
        }
    }

    LazyColumn(
        modifier = Modifier.statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 18.dp, end = 18.dp, top = 12.dp, bottom = 110.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text("Per-app routing", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Choose what goes through the TUN. XHR itself is always excluded.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            XhrCard {
                Text("Mode", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(9.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .padding(4.dp),
                ) {
                    Segment("All apps", config.splitMode == SplitMode.ALL) {
                        onChange(config.copy(splitMode = SplitMode.ALL))
                    }
                    Segment("Only selected", config.splitMode == SplitMode.ONLY) {
                        onChange(config.copy(splitMode = SplitMode.ONLY))
                    }
                    Segment("Except selected", config.splitMode == SplitMode.EXCEPT) {
                        onChange(config.copy(splitMode = SplitMode.EXCEPT))
                    }
                }
                Spacer(Modifier.height(9.dp))
                Text(
                    when (config.splitMode) {
                        SplitMode.ALL -> "Every app is tunneled. XHR and the system VPN machinery stay outside."
                        SplitMode.ONLY -> "Only the selected apps use the tunnel. Pick at least one."
                        SplitMode.EXCEPT -> "Every app except the selected apps uses the tunnel."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (config.mode == ConnMode.PROXY) {
                    Spacer(Modifier.height(8.dp))
                    InfoBanner(
                        "Proxy mode is different",
                        "In SOCKS5 mode, v2rayNG decides per-app routing. Use this screen for TUN mode.",
                        XhrColors.Yellow,
                    )
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${config.splitApps.size} selected",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Text("Show system apps", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(7.dp))
                Switch(
                    checked = config.showSystemApps,
                    onCheckedChange = { onChange(config.copy(showSystemApps = it)) },
                )
            }
        }

        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Search installed apps") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            )
        }

        if (apps.isEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(26.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Loading apps…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else if (filtered.isEmpty()) {
            item {
                InfoBanner(
                    "Nothing matches",
                    "Try another name or enable system apps.",
                    XhrColors.Blue,
                    icon = { Icon(Icons.Rounded.Apps, null, tint = XhrColors.Blue) },
                )
            }
        } else {
            items(filtered, key = { it.packageName }) { app ->
                val checked = app.packageName in config.splitApps
                Surface(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(15.dp))
                        .clickable(enabled = config.splitMode != SplitMode.ALL) {
                            if (config.splitMode != SplitMode.ALL) {
                                val next = config.splitApps.toMutableSet()
                                if (checked) next.remove(app.packageName) else next.add(app.packageName)
                                onChange(config.copy(splitApps = next))
                            }
                        },
                    color = if (checked) XhrColors.Blue.copy(alpha = 0.07f)
                    else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(15.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(38.dp).clip(RoundedCornerShape(11.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (app.icon != null) {
                                Image(
                                    app.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)),
                                )
                            } else {
                                Text(
                                    app.label.take(1).uppercase(),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                app.packageName + if (app.system) " · system" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                            enabled = config.splitMode != SplitMode.ALL,
                        )
                    }
                }
            }
        }
    }
}

private fun loadApps(context: Context, showSystem: Boolean): List<AppInfo> {
    val pm = context.packageManager
    val out = mutableListOf<AppInfo>()
    val flags = PackageManager.GET_META_DATA
    for (ai in pm.getInstalledApplications(flags)) {
        if (ai.packageName == context.packageName) continue
        val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        if (system && !showSystem) continue
        if (pm.getLaunchIntentForPackage(ai.packageName) == null) continue
        val label = runCatching { pm.getApplicationLabel(ai).toString() }
            .getOrDefault(ai.packageName)
        val icon = runCatching {
            pm.getApplicationIcon(ai).toBitmap(96, 96).asImageBitmap()
        }.getOrNull()
        out += AppInfo(ai.packageName, label, system, icon)
    }
    return out.sortedBy { it.label.lowercase() }
}
