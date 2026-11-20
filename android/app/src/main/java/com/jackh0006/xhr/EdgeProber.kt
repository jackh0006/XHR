package com.jackh0006.xhr

import android.os.Handler
import android.os.Looper
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

data class EdgeResult(
    val ip: String,
    val ok: Boolean,
    val ms: Long,
    val error: String,
    val cn: String,
    val issuer: String,
    val alpn: String,
)

data class EdgeReport(
    val results: List<EdgeResult>,
    val best: EdgeResult?,
    val error: String,
)

object EdgeProber {
    private val main = Handler(Looper.getMainLooper())
    private val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    fun defaultCandidates(): List<String> = listOf(
        "216.239.38.120",
        "216.239.32.120",
        "216.239.34.120",
        "216.239.36.120",
        "142.250.190.78",
        "142.250.191.78",
        "142.251.32.78",
        "172.217.0.238",
        "172.217.12.238",
        "172.217.14.238",
        "172.217.16.238",
        "172.217.18.238",
        "172.217.20.238",
        "172.217.22.238",
        "173.194.73.120",
        "74.125.24.120",
    )

    fun parseCandidates(raw: String): List<String> {
        val out = LinkedHashSet<String>()
        for (part in raw.split(Regex("[,\\s]+"))) {
            if (out.size >= 24) break
            val value = part.trim()
            if (!ipv4.matches(value)) continue
            val valid = value.split('.').all { (it.toIntOrNull() ?: 256) in 0..255 }
            if (valid) out += value.split('.').joinToString(".") { it.toInt().toString() }
        }
        return out.toList()
    }

    fun run(ips: List<String>, sni: String, callback: (EdgeReport) -> Unit) {
        val candidates = ips.distinct().take(24)
        if (candidates.isEmpty()) {
            main.post {
                callback(EdgeReport(emptyList(), null, "Add at least one valid IPv4 address."))
            }
            return
        }
        thread(name = "xhr-edge-probe", isDaemon = true) {
            val results = Collections.synchronizedList(mutableListOf<EdgeResult>())
            val latch = CountDownLatch(candidates.size)
            for (ip in candidates) {
                thread(name = "xhr-probe-$ip", isDaemon = true) {
                    try {
                        val r = Engine.testSni(ip, sni)
                        results += EdgeResult(ip, r.ok, r.ms, r.error, r.cn, r.issuer, r.alpn)
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await()
            val sorted = results.sortedWith(
                compareBy<EdgeResult> { !it.ok }.thenBy { it.ms }
            )
            val best = sorted.firstOrNull { it.ok }
            val error = if (best == null) "No candidate completed a TLS handshake." else ""
            main.post { callback(EdgeReport(sorted, best, error)) }
        }
    }
}
