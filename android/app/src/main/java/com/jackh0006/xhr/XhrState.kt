package com.jackh0006.xhr

import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

data class XhrSnapshot(
    val running: Boolean = false,
    val starting: Boolean = false,
    val mode: ConnMode = ConnMode.TUN,
    val phase: String = "idle",
    val error: String? = null,
    val up: Long = 0,
    val down: Long = 0,
    val upRate: Long = 0,
    val downRate: Long = 0,
    val calls: Long = 0,
    val streams: Long = 0,
    val active: Long = 0,
    val startedAt: Long = 0,
    val port: Int = 0,
)

object XhrState {
    val flow = MutableStateFlow(XhrSnapshot())

    fun starting(mode: ConnMode, port: Int) {
        flow.value = XhrSnapshot(
            starting = true,
            mode = mode,
            phase = "starting",
            port = port,
        )
    }

    fun running(mode: ConnMode, port: Int) {
        flow.value = XhrSnapshot(
            running = true,
            mode = mode,
            phase = "running",
            startedAt = System.currentTimeMillis(),
            port = port,
        )
    }

    fun traffic(
        up: Long,
        down: Long,
        upRate: Long,
        downRate: Long,
        calls: Long,
        streams: Long,
        active: Long,
    ) {
        flow.value = flow.value.copy(
            up = up,
            down = down,
            upRate = upRate,
            downRate = downRate,
            calls = calls,
            streams = streams,
            active = active,
        )
    }

    fun failed(mode: ConnMode, message: String) {
        flow.value = XhrSnapshot(mode = mode, phase = "error", error = message)
    }

    fun stopped() {
        flow.value = XhrSnapshot(phase = "idle")
    }
}

data class LogLine(val time: String, val text: String)

object XhrLog {
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    val flow = MutableStateFlow<List<LogLine>>(emptyList())

    fun add(tag: String, message: String) {
        val line = LogLine(clock.format(Date()), "[$tag] $message")
        flow.value = (flow.value + line).takeLast(600)
    }

    fun addRaw(raw: String) {
        if (raw.isBlank()) return
        val lines = raw.lineSequence().filter { it.isNotBlank() }.map {
            val split = it.indexOf(' ')
            if (split in 1..9) LogLine(it.substring(0, split), it.substring(split + 1))
            else LogLine(clock.format(Date()), it)
        }.toList()
        if (lines.isNotEmpty()) flow.value = (flow.value + lines).takeLast(600)
    }

    fun clear() {
        flow.value = emptyList()
        runCatching { Native.clearLogs() }
    }
}

object Format {
    fun bytes(value: Long): String {
        val v = abs(value).toDouble()
        return when {
            v >= 1024 * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", v / (1024 * 1024 * 1024))
            v >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", v / (1024 * 1024))
            v >= 1024 -> String.format(Locale.US, "%.1f KB", v / 1024)
            else -> "$value B"
        }
    }

    fun rate(value: Long): String = bytes(value) + "/s"

    fun duration(millis: Long): String {
        val s = (millis / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%02d:%02d", m, sec)
    }

    fun number(value: Long): String =
        String.format(Locale.US, "%,d", value)
}
