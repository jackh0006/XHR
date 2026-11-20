package com.jackh0006.xhr

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

/**
 * Proxy-only mode. The engine exposes a SOCKS5 listener on 127.0.0.1. This is
 * the mode to select inside v2rayNG or any other client that supports a SOCKS5
 * outbound. It never creates a VPN interface, so it can sit behind another
 * VPN app when that app is configured to exclude XHR itself.
 */
class ProxyService : android.app.Service() {
    companion object {
        const val ACTION_START = "com.jackh0006.xhr.action.START_PROXY"
        const val ACTION_STOP = "com.jackh0006.xhr.action.STOP_PROXY"
        const val NOTIFICATION_ID = 72

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var isStarting: Boolean = false

        fun start(context: Context) {
            val intent = Intent(context, ProxyService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, ProxyService::class.java).setAction(ACTION_STOP)
                )
            }.onFailure {
                context.stopService(Intent(context, ProxyService::class.java))
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var worker: Thread? = null
    private var config: XhrConfig? = null
    private var engineStarted = false
    private var tracker: TrafficTracker? = null

    @Volatile
    private var shuttingDown = false

    private val tick = object : Runnable {
        override fun run() {
            if (shuttingDown) return
            Engine.pumpLogs()
            val cfg = config ?: return
            val stats = Engine.stats()
            if (stats == null) {
                handler.postDelayed(this, 1_000)
                return
            }
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
            XhrLog.add("proxy", "Stop requested")
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
        if (!startInForeground()) return START_NOT_STICKY

        XhrState.starting(ConnMode.PROXY, cfg.port)
        XhrLog.add("proxy", "Starting SOCKS5 proxy on 127.0.0.1:${cfg.port}")
        runCatching { stopService(Intent(this, XhrVpnService::class.java)) }

        worker = thread(name = "xhr-proxy", isDaemon = true) {
            try {
                if (shuttingDown) return@thread
                Thread.sleep(300)
                if (shuttingDown) return@thread
                Engine.stop()
                val error = Engine.start(cfg)
                if (error != null) {
                    fail(error)
                    return@thread
                }
                engineStarted = true
                if (shuttingDown) {
                    cleanup()
                    return@thread
                }
                tracker = TrafficTracker(this, cfg.dailyLimitMb)
                isRunning = true
                isStarting = false
                XhrState.running(ConnMode.PROXY, cfg.port)
                XhrLog.add("proxy", "SOCKS5 ready · point v2rayNG at 127.0.0.1:${cfg.port}")
                updateNotification(null, null)
                handler.postDelayed(tick, 800)
            } catch (t: Throwable) {
                if (!shuttingDown) fail(t.message ?: getString(R.string.error_start_failed))
            }
        }
        return START_STICKY
    }

    private fun startInForeground(): Boolean {
        val notification = buildStatusNotification(
            this, ConnMode.PROXY, null, null,
            ProxyService::class.java, ACTION_STOP,
        )
        return try {
            Foreground.start(this, NOTIFICATION_ID, notification)
            true
        } catch (t: Throwable) {
            fail(t.message ?: getString(R.string.error_start_failed))
            false
        }
    }

    private fun updateNotification(stats: EngineStats?, sample: RateSample?) {
        val notification = buildStatusNotification(
            this, ConnMode.PROXY, stats, sample,
            ProxyService::class.java, ACTION_STOP,
        )
        XhrNotifications.update(this, NOTIFICATION_ID, notification)
    }

    private fun fail(message: String) {
        XhrLog.add("proxy", "Failed: $message")
        XhrState.failed(ConnMode.PROXY, message)
        shutdown(setStopped = false)
    }

    @Synchronized
    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
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

    override fun onDestroy() {
        if (!shuttingDown) {
            shuttingDown = true
            cleanup()
        }
        val current = XhrState.flow.value
        if (current.phase != "error" && current.mode == ConnMode.PROXY && !current.starting) {
            XhrState.stopped()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
