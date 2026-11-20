package com.jackh0006.xhr

import android.os.Handler
import android.os.Looper
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

data class ProxyTestResult(
    val ok: Boolean,
    val exitIp: String,
    val ms: Long,
    val error: String,
)

/**
 * Performs a real HTTPS request through the local SOCKS5 listener. This proves
 * the complete chain client -> Google edge -> Apps Script -> exit -> Internet,
 * not just that a port is open.
 */
object ConnectionTester {
    private val main = Handler(Looper.getMainLooper())

    fun run(port: Int, callback: (ProxyTestResult) -> Unit) {
        thread(name = "xhr-connection-test", isDaemon = true) {
            val result = test(port)
            main.post { callback(result) }
        }
    }

    private fun test(port: Int): ProxyTestResult {
        XhrLog.add("test", "Testing the full relay path")
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        val started = System.currentTimeMillis()
        val error = AtomicReference<String?>(null)
        val exitIp = AtomicReference("")

        try {
            val probe = URL("https://www.gstatic.com/generate_204")
                .openConnection(proxy) as HttpsURLConnection
            probe.connectTimeout = 20_000
            probe.readTimeout = 20_000
            probe.instanceFollowRedirects = false
            probe.requestMethod = "GET"
            probe.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) XHR")
            val code = probe.responseCode
            probe.disconnect()
            if (code !in 200..399) {
                error.set("HTTPS probe returned $code")
            }
        } catch (t: Throwable) {
            error.set(t.message ?: "HTTPS probe failed")
        }

        if (error.get() == null) {
            try {
                val ip = URL("https://api.ipify.org")
                    .openConnection(proxy) as HttpsURLConnection
                ip.connectTimeout = 15_000
                ip.readTimeout = 15_000
                ip.requestMethod = "GET"
                ip.setRequestProperty("User-Agent", "XHR")
                val code = ip.responseCode
                if (code == 200) {
                    exitIp.set(ip.inputStream.bufferedReader().use { it.readText() }
                        .trim().takeIf { it.length in 7..64 } ?: "")
                }
                ip.disconnect()
            } catch (t: Throwable) {
                if (error.get() == null) error.set(t.message ?: "Exit IP lookup failed")
            }
        }

        val elapsed = System.currentTimeMillis() - started
        val err = error.get()
        val result = ProxyTestResult(
            ok = err == null,
            exitIp = exitIp.get(),
            ms = elapsed,
            error = err ?: "",
        )
        if (result.ok) {
            XhrLog.add(
                "test",
                "Relay OK in ${elapsed} ms" +
                    if (result.exitIp.isNotEmpty()) " · exit IP ${result.exitIp}" else ""
            )
        } else {
            XhrLog.add("test", "Relay failed: ${result.error}")
        }
        return result
    }
}
