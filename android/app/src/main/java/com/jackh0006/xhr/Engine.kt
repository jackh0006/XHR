package com.jackh0006.xhr

import org.json.JSONObject

data class EngineStats(
    val up: Long,
    val down: Long,
    val calls: Long,
    val streams: Long,
    val active: Long,
    val running: Boolean,
    val error: String,
)

data class SniResult(
    val ok: Boolean,
    val ms: Long,
    val error: String,
    val cn: String,
    val issuer: String,
    val alpn: String,
)

object Engine {
    @Volatile
    private var owner: String? = null

    fun availableError(): String? = Native.error()

    @Synchronized
    fun start(service: String, cfg: XhrConfig): String? {
        availableError()?.let { return "Native engine unavailable: $it" }
        return runCatching {
            // A previous service can leave a listener behind. Stop it first so
            // a restart is deterministic, then claim ownership.
            runCatching { Native.stop() }
            owner = null
            val error = Native.start(cfg.engineJson())
            if (error.isBlank()) owner = service
            error.ifBlank { null }
        }.getOrElse { it.message ?: "Native engine failed to start" }
    }

    fun ownedBy(service: String): Boolean = owner == service

    /** Stop only if this service still owns the engine. */
    @Synchronized
    fun stop(service: String) {
        if (owner != service) return
        owner = null
        if (availableError() != null) return
        runCatching { Native.stop() }
    }

    @Synchronized
    fun stopAll() {
        owner = null
        if (availableError() != null) return
        runCatching { Native.stop() }
    }

    fun stats(): EngineStats? {
        if (availableError() != null) return null
        return runCatching {
            val o = JSONObject(Native.stats())
            EngineStats(
                up = o.optLong("up", 0),
                down = o.optLong("down", 0),
                calls = o.optLong("calls", 0),
                streams = o.optLong("streams", 0),
                active = o.optLong("active", 0),
                running = o.optBoolean("running", false),
                error = o.optString("error", ""),
            )
        }.getOrNull()
    }

    fun pumpLogs() {
        if (availableError() != null) return
        runCatching { XhrLog.addRaw(Native.logs()) }
    }

    fun version(): String {
        if (availableError() != null) return "unavailable"
        return runCatching { Native.version() }.getOrDefault("unavailable")
    }

    fun testSni(ip: String, sni: String, port: Int = 443): SniResult {
        availableError()?.let { return SniResult(false, 0, it, "", "", "") }
        val request = JSONObject()
            .put("ip", ip)
            .put("sni", sni)
            .put("port", port)
            .toString()
        return runCatching {
            val o = JSONObject(Native.testSni(request))
            SniResult(
                ok = o.optBoolean("ok", false),
                ms = o.optLong("ms", 0),
                error = o.optString("error", ""),
                cn = o.optString("cn", ""),
                issuer = o.optString("issuer", ""),
                alpn = o.optString("alpn", ""),
            )
        }.getOrElse { SniResult(false, 0, it.message ?: "probe failed", "", "", "") }
    }
}
