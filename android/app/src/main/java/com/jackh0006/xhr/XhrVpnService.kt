package com.jackh0006.xhr

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import hev.htproxy.TProxyService
import java.io.File
import kotlin.concurrent.thread

/**
 * System-wide TUN mode.
 *
 * The Go engine exposes an authenticated, multiplexed SOCKS5 listener on
 * loopback. hev-socks5-tunnel converts the Android TUN descriptor into SOCKS5
 * sessions, and mapdns resolves names at the exit instead of on the phone.
 * XHR's own socket is excluded from the tunnel so it cannot loop.
 */
class XhrVpnService : VpnService() {
    companion object {
        const val ACTION_START = "com.jackh0006.xhr.action.START_VPN"
        const val ACTION_STOP = "com.jackh0006.xhr.action.STOP_VPN"
        const val NOTIFICATION_ID = 71
        private const val OWNER = "vpn"
        private const val TUN_ADDRESS = "198.18.0.1"
        private const val TUN_DNS = "198.18.0.2"
        private const val TUN_IPV6 = "fc00::1"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var isStarting: Boolean = false

        fun start(context: Context) {
            val intent = Intent(context, XhrVpnService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, XhrVpnService::class.java).setAction(ACTION_STOP)
                )
            }.onFailure {
                context.stopService(Intent(context, XhrVpnService::class.java))
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var tun: ParcelFileDescriptor? = null
    private var hevStarted = false
    private var engineStarted = false
    private var worker: Thread? = null
    private var config: XhrConfig? = null
    private var tracker: TrafficTracker? = null

    @Volatile
    private var shuttingDown = false

    private val tick = object : Runnable {
        override fun run() {
            if (shuttingDown) return
            Engine.pumpLogs()
            if (!runCatching { TProxyService.TProxyIsRunning() }.getOrDefault(false)) {
                fail("Tunnel engine stopped unexpectedly")
                return
            }
            val cfg = config ?: return
            val stats = Engine.stats() ?: return
            val sample = tracker?.sample(stats) ?: return
            XhrState.traffic(
                stats.up, stats.down, sample.upRate, sample.downRate,
                stats.calls, stats.streams, stats.active,
            )
            if (cfg.dailyLimitMb > 0 &&
                sample.daily.total >= cfg.dailyLimitMb.toLong() * 1024L * 1024L
            ) {
                fail("Daily data limit reached (${cfg.dailyLimitMb} MB)")
                return
            }
            updateNotification(stats, sample)
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        XhrNotifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            XhrLog.add("vpn", "Stop requested")
            shutdown(setStopped = true)
            return START_NOT_STICKY
        }
        if (isRunning || isStarting) return START_STICKY
        shuttingDown = false
        isStarting = true

        val cfg = ConfigStore.load(this)
        val invalid = cfg.validationError()
        if (invalid != null) {
            fail(invalid)
            return START_NOT_STICKY
        }
        config = cfg
        if (!startInForeground(cfg)) return START_NOT_STICKY

        XhrState.starting(ConnMode.TUN, cfg.port)
        XhrLog.add("vpn", "Starting TUN mode")

        // Only one XHR transport may own the loopback port.
        runCatching { stopService(Intent(this, ProxyService::class.java)) }

        worker = thread(name = "xhr-vpn", isDaemon = true) {
            try {
                if (shuttingDown) return@thread
                Thread.sleep(250)
                if (shuttingDown) return@thread
                Engine.stop()
                val engineError = Engine.start(cfg)
                if (engineError != null) {
                    fail(engineError)
                    return@thread
                }
                engineStarted = true
                if (shuttingDown) return@thread

                val fd = establishTunnel(cfg)
                if (fd == null) {
                    fail(getString(R.string.error_vpn_denied))
                    return@thread
                }
                tun = fd
                val path = writeHevConfig(cfg)
                if (!TProxyService.TProxyStartService(path.absolutePath, fd.fd)) {
                    fail(getString(R.string.error_tunnel_engine))
                    return@thread
                }
                hevStarted = true
                if (shuttingDown) {
                    cleanup()
                    return@thread
                }

                tracker = TrafficTracker(this, cfg.dailyLimitMb)
                isRunning = true
                isStarting = false
                XhrState.running(ConnMode.TUN, cfg.port)
                XhrLog.add(
                    "vpn",
                    "TUN up · ${cfg.splitMode.name.lowercase()} per-app · " +
                        "MTU ${cfg.mtu} · edge ${cfg.googleIp}"
                )
                updateNotification(null, null)
                handler.postDelayed(tick, 1_200)
            } catch (t: Throwable) {
                if (!shuttingDown) fail(t.message ?: getString(R.string.error_start_failed))
            }
        }
        return START_STICKY
    }

    private fun startInForeground(cfg: XhrConfig): Boolean {
        val notification = buildStatusNotification(
            this, ConnMode.TUN, null, null,
            XhrVpnService::class.java, ACTION_STOP,
        )
        return try {
            Foreground.start(this, NOTIFICATION_ID, notification)
            true
        } catch (t: Throwable) {
            fail(t.message ?: getString(R.string.error_start_failed))
            false
        }
    }

    private fun establishTunnel(cfg: XhrConfig): ParcelFileDescriptor? {
        val configure = PendingIntent.getActivity(
            this,
            31,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(cfg.mtu)
            .setConfigureIntent(configure)
            .setBlocking(false)
            .addAddress(TUN_ADDRESS, 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(TUN_DNS)
            .addAddress(TUN_IPV6, 128)
            .addRoute("::", 0)

        when (cfg.splitMode) {
            SplitMode.ALL -> {
                builder.addDisallowedApplication(packageName)
            }
            SplitMode.EXCEPT -> {
                builder.addDisallowedApplication(packageName)
                for (pkg in cfg.splitApps) {
                    if (pkg == packageName) continue
                    runCatching { builder.addDisallowedApplication(pkg) }
                        .onFailure { if (it !is PackageManager.NameNotFoundException) throw it }
                }
            }
            SplitMode.ONLY -> {
                var added = 0
                for (pkg in cfg.splitApps) {
                    if (pkg == packageName) continue
                    try {
                        builder.addAllowedApplication(pkg)
                        added++
                    } catch (_: PackageManager.NameNotFoundException) {
                        // Uninstalled since it was selected.
                    }
                }
                if (added == 0) {
                    throw IllegalStateException(getString(R.string.error_no_per_app))
                }
            }
        }
        return builder.establish()
    }

    private fun writeHevConfig(cfg: XhrConfig): File {
        val file = File(filesDir, "xhr-tun.yml")
        file.writeText(
            buildString {
                appendLine("tunnel:")
                appendLine("  name: tun0")
                appendLine("  mtu: ${cfg.mtu}")
                appendLine("  multi-queue: false")
                appendLine("  ipv4: $TUN_ADDRESS")
                appendLine("  ipv6: '$TUN_IPV6'")
                appendLine("  icmp: 'off'")
                appendLine("socks5:")
                appendLine("  port: ${cfg.port}")
                appendLine("  address: 127.0.0.1")
                appendLine("  udp: 'udp'")
                appendLine("mapdns:")
                appendLine("  address: $TUN_DNS")
                appendLine("  port: 53")
                appendLine("  network: 100.64.0.0")
                appendLine("  netmask: 255.192.0.0")
                appendLine("  cache-size: 10000")
                appendLine("misc:")
                appendLine("  log-level: error")
                appendLine("  connect-timeout: 45000")
                appendLine("  tcp-read-write-timeout: 600000")
                appendLine("  udp-read-write-timeout: 60000")
                appendLine("  max-session-count: 256")
            }
        )
        return file
    }

    private fun updateNotification(stats: EngineStats?, sample: RateSample?) {
        val cfg = config ?: return
        val notification = buildStatusNotification(
            this, ConnMode.TUN, stats, sample,
            XhrVpnService::class.java, ACTION_STOP,
        )
        XhrNotifications.update(this, NOTIFICATION_ID, notification)
    }

    private fun fail(message: String) {
        XhrLog.add("vpn", "Failed: $message")
        XhrState.failed(ConnMode.TUN, message)
        shutdown(setStopped = false)
    }

    @Synchronized
    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        if (hevStarted) {
            runCatching { TProxyService.TProxyStopService() }
            hevStarted = false
        }
        runCatching { tun?.close() }
        tun = null
        if (engineStarted) {
            Engine.stop()
            engineStarted = false
        }
        isRunning = false
        isStarting = false
        Foreground.stop(this)
    }

    @Synchronized
    private fun shutdown(setStopped: Boolean) {
        if (shuttingDown) return
        shuttingDown = true
        cleanup()
        if (setStopped && XhrState.flow.value.phase != "error") {
            XhrState.stopped()
        }
        stopSelf()
    }

    override fun onRevoke() {
        XhrLog.add("vpn", "VPN permission revoked by the system")
        fail("VPN permission revoked")
        super.onRevoke()
    }

    override fun onDestroy() {
        val wasShuttingDown = shuttingDown
        if (!wasShuttingDown) {
            shuttingDown = true
            cleanup()
        }
        val current = XhrState.flow.value
        if (current.phase != "error" && current.mode == ConnMode.TUN && !current.starting) {
            XhrState.stopped()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = super.onBind(intent)
}
