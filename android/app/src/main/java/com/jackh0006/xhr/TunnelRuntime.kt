package com.jackh0006.xhr

import android.app.Notification
import android.content.Context

data class RateSample(
    val upRate: Long,
    val downRate: Long,
    val daily: DailySnapshot,
)

/** Converts cumulative engine counters into live rates and daily usage. */
class TrafficTracker(
    private val context: Context,
    private val limitMb: Int,
) {
    private var lastUp = 0L
    private var lastDown = 0L
    private var lastCalls = 0L
    private var lastAt = System.currentTimeMillis()

    fun sample(stats: EngineStats): RateSample {
        val now = System.currentTimeMillis()
        val dt = (now - lastAt).coerceAtLeast(1)
        val dUp = (stats.up - lastUp).coerceAtLeast(0)
        val dDown = (stats.down - lastDown).coerceAtLeast(0)
        val dCalls = (stats.calls - lastCalls).coerceAtLeast(0)
        lastUp = stats.up
        lastDown = stats.down
        lastCalls = stats.calls
        lastAt = now
        DailyUsage.add(context, dUp, dDown, dCalls, limitMb)
        return RateSample(
            upRate = dUp * 1000 / dt,
            downRate = dDown * 1000 / dt,
            daily = DailyUsage.flow.value,
        )
    }
}

fun buildStatusNotification(
    context: Context,
    mode: ConnMode,
    stats: EngineStats?,
    sample: RateSample?,
    serviceClass: Class<*>,
    stopAction: String,
): Notification {
    val daily = sample?.daily ?: DailyUsage.flow.value
    val title = if (mode == ConnMode.TUN) {
        context.getString(R.string.notif_vpn_title)
    } else {
        context.getString(R.string.notif_proxy_title)
    }
    val text = if (stats == null) {
        context.getString(R.string.notif_starting)
    } else {
        "↑ ${Format.rate(sample?.upRate ?: 0)}   ↓ ${Format.rate(sample?.downRate ?: 0)}" +
            "   ·   ${stats.active} active"
    }
    val big = if (stats == null) {
        "Encrypted TCP relay · AES-256-GCM"
    } else {
        "Upload ${Format.bytes(stats.up)}  ·  Download ${Format.bytes(stats.down)}\n" +
            "Today ${Format.number(daily.calls)} / 20,000 relay requests  ·  " +
            "${Format.bytes(daily.total)} data\n" +
            "Session ${if (mode == ConnMode.TUN) "TUN" else "SOCKS5 127.0.0.1:${XhrState.flow.value.port}"}"
    }
    return XhrNotifications.build(
        context, mode, title, text, big, serviceClass, stopAction,
    )
}
