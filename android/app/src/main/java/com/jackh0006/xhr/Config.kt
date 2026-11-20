package com.jackh0006.xhr

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

enum class ConnMode { TUN, PROXY }
enum class SplitMode { ALL, ONLY, EXCEPT }

data class XhrConfig(
    var gasUrl: String = "",
    var authKey: String = "",
    var e2eKey: String = "",
    var googleIp: String = "216.239.38.120",
    var frontDomain: String = "www.google.com",
    var port: Int = 1080,
    var mode: ConnMode = ConnMode.TUN,
    var splitMode: SplitMode = SplitMode.ALL,
    var splitApps: Set<String> = emptySet(),
    var mtu: Int = 1500,
    var dailyLimitMb: Int = 0,
    var showSystemApps: Boolean = false,
) {
    fun resolvedUrl(): String {
        val raw = gasUrl.trim()
        if (raw.startsWith("AKfycb")) {
            return "https://script.google.com/macros/s/$raw/exec"
        }
        if (raw.startsWith("https://script.google.com/macros/s/") && !raw.endsWith("/exec")) {
            return raw.trimEnd('/') + "/exec"
        }
        return raw
    }

    fun validationError(): String? = when {
        gasUrl.isBlank() -> "Deployment ID or URL is required"
        !resolvedUrl().startsWith("https://") -> "Deployment URL must start with https://"
        authKey.isBlank() -> "AUTH_KEY is required"
        e2eKey.length != 64 || e2eKey.any { it !in "0123456789abcdefABCDEF" } ->
            "Tunnel key must be exactly 64 hex characters"
        googleIp.isBlank() -> "Google edge IP is required"
        frontDomain.isBlank() -> "Front domain is required"
        port !in 1024..65535 -> "Port must be between 1024 and 65535"
        mtu !in 1200..1500 -> "MTU must be between 1200 and 1500"
        dailyLimitMb < 0 -> "Daily limit cannot be negative"
        else -> null
    }

    fun engineJson(): String = JSONObject().apply {
        put("gas_url", resolvedUrl())
        put("gas_host", "script.google.com")
        put("front_domain", frontDomain.trim().ifBlank { "www.google.com" })
        put("google_ip", googleIp.trim())
        put("auth_key", authKey)
        put("e2e_key", e2eKey.lowercase())
        put("listen_port", port)
    }.toString()

    fun toJson(): String = JSONObject().apply {
        put("gas", gasUrl)
        put("auth", authKey)
        put("e2e", e2eKey)
        put("ip", googleIp)
        put("front", frontDomain)
        put("port", port)
        put("mode", mode.name)
        put("split", splitMode.name)
        put("apps", JSONArray(splitApps.toList()))
        put("mtu", mtu)
        put("limit", dailyLimitMb)
        put("system", showSystemApps)
    }.toString()

    companion object {
        fun fromJson(raw: String): XhrConfig {
            val o = JSONObject(raw)
            val apps = mutableSetOf<String>()
            o.optJSONArray("apps")?.let { arr ->
                for (i in 0 until arr.length()) apps += arr.optString(i)
            }
            return XhrConfig(
                gasUrl = o.optString("gas", ""),
                authKey = o.optString("auth", ""),
                e2eKey = o.optString("e2e", ""),
                googleIp = o.optString("ip", "216.239.38.120"),
                frontDomain = o.optString("front", "www.google.com"),
                port = o.optInt("port", 1080).coerceIn(1024, 65535),
                mode = runCatching { ConnMode.valueOf(o.optString("mode", "TUN")) }
                    .getOrDefault(ConnMode.TUN),
                splitMode = runCatching { SplitMode.valueOf(o.optString("split", "ALL")) }
                    .getOrDefault(SplitMode.ALL),
                splitApps = apps,
                mtu = o.optInt("mtu", 1500).coerceIn(1200, 1500),
                dailyLimitMb = o.optInt("limit", 0).coerceAtLeast(0),
                showSystemApps = o.optBoolean("system", false),
            )
        }
    }
}

/**
 * Stores the profile encrypted with an AES-256-GCM key held by Android
 * Keystore. The key is non-exportable. If a device rejects the keystore
 * provider the value is kept in the app's private data directory, which is
 * still sandboxed, so the app remains usable.
 */
object ConfigStore {
    private const val PREFS = "xhr.profile.v1"
    private const val KEY = "sealed"
    private const val ALIAS = "xhr.profile.key.v1"

    fun load(ctx: Context): XhrConfig {
        val stored = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return XhrConfig()
        return runCatching {
            XhrConfig.fromJson(Box.open(stored))
        }.getOrDefault(XhrConfig())
    }

    fun save(ctx: Context, cfg: XhrConfig) {
        val blob = Box.seal(cfg.toJson())
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, blob).apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private object Box {
        private fun key(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return gen.generateKey()
        }

        fun seal(plain: String): String = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            "v1:" + Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ct, Base64.NO_WRAP)
        } catch (_: Throwable) {
            "plain:" + Base64.encodeToString(plain.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }

        fun open(stored: String): String {
            if (stored.startsWith("plain:")) {
                return String(Base64.decode(stored.removePrefix("plain:"), Base64.NO_WRAP))
            }
            val parts = stored.removePrefix("v1:").split(":")
            require(parts.size == 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            return String(cipher.doFinal(ct), Charsets.UTF_8)
        }
    }
}

data class DailySnapshot(
    val date: String = "",
    val up: Long = 0,
    val down: Long = 0,
    val calls: Long = 0,
    val limitBytes: Long = 0,
) {
    val callsLimit: Long get() = 20_000
    val total: Long get() = up + down
}

/** Local-only daily usage. Nothing is uploaded anywhere. */
object DailyUsage {
    private const val PREFS = "xhr.daily.v1"
    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    val flow = MutableStateFlow(DailySnapshot(date = today()))

    fun refresh(ctx: Context, limitMb: Int = 0) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = today()
        if (p.getString("date", "") != day) {
            p.edit().putString("date", day).putLong("up", 0).putLong("down", 0)
                .putLong("calls", 0).apply()
            flow.value = DailySnapshot(date = day, limitBytes = limitMb * 1024L * 1024L)
            return
        }
        flow.value = DailySnapshot(
            date = day,
            up = p.getLong("up", 0),
            down = p.getLong("down", 0),
            calls = p.getLong("calls", 0),
            limitBytes = limitMb * 1024L * 1024L,
        )
    }

    fun add(ctx: Context, up: Long, down: Long, calls: Long, limitMb: Int) {
        if (up <= 0 && down <= 0 && calls <= 0) {
            refresh(ctx, limitMb)
            return
        }
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = today()
        if (p.getString("date", "") != day) {
            p.edit().putString("date", day).putLong("up", 0).putLong("down", 0)
                .putLong("calls", 0).apply()
        }
        val nextUp = p.getLong("up", 0) + up.coerceAtLeast(0)
        val nextDown = p.getLong("down", 0) + down.coerceAtLeast(0)
        val nextCalls = p.getLong("calls", 0) + calls.coerceAtLeast(0)
        p.edit().putLong("up", nextUp).putLong("down", nextDown)
            .putLong("calls", nextCalls).apply()
        flow.value = DailySnapshot(
            date = day,
            up = nextUp,
            down = nextDown,
            calls = nextCalls,
            limitBytes = limitMb * 1024L * 1024L,
        )
    }

    fun reset(ctx: Context, limitMb: Int = 0) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putString("date", today()).putLong("up", 0).putLong("down", 0)
            .putLong("calls", 0).apply()
        refresh(ctx, limitMb)
    }
}
