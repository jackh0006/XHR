package com.jackh0006.xhr

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jackh0006.xhr.ui.AboutScreen
import com.jackh0006.xhr.ui.AppsScreen
import com.jackh0006.xhr.ui.EdgeScreen
import com.jackh0006.xhr.ui.HomeScreen
import com.jackh0006.xhr.ui.LogsScreen
import com.jackh0006.xhr.ui.SettingsScreen
import com.jackh0006.xhr.ui.SetupScreen
import com.jackh0006.xhr.ui.XhrTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        XhrNotifications.ensureChannel(this)
        setContent { XhrTheme { XhrApp() } }
    }
}

private enum class Tab(val label: String) {
    HOME("Home"),
    EDGE("Edge"),
    APPS("Apps"),
    LOGS("Logs"),
    ABOUT("About"),
}

@Composable
private fun XhrApp() {
    val context = LocalContext.current
    val activity = context as? Activity
    var config by remember { mutableStateOf(ConfigStore.load(context)) }
    var showSetup by remember { mutableStateOf(config.validationError() != null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }

    val snapshot by XhrState.flow.collectAsState()
    val daily by DailyUsage.flow.collectAsState()

    LaunchedEffect(config.dailyLimitMb) {
        DailyUsage.refresh(context, config.dailyLimitMb)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, config.dailyLimitMb) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                DailyUsage.refresh(context, config.dailyLimitMb)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                context,
                "Notifications are off. The tunnel still runs, but the status bar counter is hidden.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val vpnPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            XhrVpnService.start(context)
        } else {
            Toast.makeText(context, "VPN permission is required for TUN mode.", Toast.LENGTH_LONG).show()
        }
    }

    fun persist(next: XhrConfig) {
        config = next
        ConfigStore.save(context, next)
    }

    fun start() {
        if (config.validationError() != null) {
            showSetup = true
            return
        }
        if (config.mode == ConnMode.TUN) {
            val prepare = VpnService.prepare(context)
            if (prepare != null) vpnPermission.launch(prepare) else XhrVpnService.start(context)
        } else {
            ProxyService.start(context)
        }
    }

    fun stop() {
        if (snapshot.mode == ConnMode.TUN) XhrVpnService.stop(context) else ProxyService.stop(context)
    }

    if (showSetup) {
        SetupScreen(
            initial = config,
            onSave = { next ->
                persist(next)
                showSetup = false
                showSettings = false
            },
            onCancel = if (config.validationError() == null) {
                { showSetup = false }
            } else null,
        )
        return
    }

    val gradient = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        Brush.verticalGradient(listOf(Color(0xFF0B1020), Color(0xFF0E162A)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFF8FAFF), Color(0xFFF3F6FC)))
    }

    Box(Modifier.fillMaxSize().background(gradient)) {
        if (showSettings) {
            SettingsScreen(
                config = config,
                onSave = { next ->
                    persist(next)
                    showSettings = false
                },
                onBack = { showSettings = false },
                onEditProfile = { showSetup = true },
                onResetProfile = {
                    if (snapshot.running) stop()
                    ConfigStore.clear(context)
                    DailyUsage.reset(context)
                    config = XhrConfig()
                    showSettings = false
                    showSetup = true
                },
            )
        } else {
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                    ) {
                        NavItem("Home", Icons.Rounded.Home, tab == Tab.HOME) { tab = Tab.HOME }
                        NavItem("Edge", Icons.Rounded.Public, tab == Tab.EDGE) { tab = Tab.EDGE }
                        NavItem("Apps", Icons.Rounded.Apps, tab == Tab.APPS) { tab = Tab.APPS }
                        NavItem("Logs", Icons.Rounded.Article, tab == Tab.LOGS) { tab = Tab.LOGS }
                        NavItem("About", Icons.Rounded.Info, tab == Tab.ABOUT) { tab = Tab.ABOUT }
                    }
                },
            ) { _ ->
                when (tab) {
                    Tab.HOME -> HomeScreen(
                        config = config,
                        snapshot = snapshot,
                        daily = daily,
                        onSettings = { showSettings = true },
                        onModeChange = { mode -> persist(config.copy(mode = mode)) },
                        onStart = { start() },
                        onStop = { stop() },
                        onOpenLogs = { tab = Tab.LOGS },
                        onOpenEdge = { tab = Tab.EDGE },
                    )
                    Tab.EDGE -> EdgeScreen(
                        config = config,
                        onUseIp = { ip ->
                            persist(config.copy(googleIp = ip))
                            Toast.makeText(context, "Saved edge $ip", Toast.LENGTH_SHORT).show()
                        },
                    )
                    Tab.APPS -> AppsScreen(
                        config = config,
                        onChange = { next -> persist(next) },
                    )
                    Tab.LOGS -> LogsScreen(snapshot)
                    Tab.ABOUT -> AboutScreen(onOpenSettings = { showSettings = true })
                }
            }
        }
    }
}

@Composable
private fun RowScope.NavItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label) },
    )
}

private fun Color.luminance(): Float =
    0.2126f * red + 0.7152f * green + 0.0722f * blue
