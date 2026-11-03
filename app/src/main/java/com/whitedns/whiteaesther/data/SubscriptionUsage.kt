package com.whitedns.whiteaesther.data

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.Base64

/** Quota values reported by the subscription provider; missing fields stay unknown. */
data class SubscriptionUsage(
    val name: String,
    val uploadBytes: Long? = null,
    val downloadBytes: Long? = null,
    val totalBytes: Long? = null,
    val expiresAtSeconds: Long? = null,
    val neverExpires: Boolean = false,
    val unlimited: Boolean = false,
) {
    val usedBytes: Long? get() {
        val up = uploadBytes ?: return null
        val down = downloadBytes ?: return null
        return if (Long.MAX_VALUE - up < down) null else up + down
    }
    val remainingBytes: Long? get() = totalBytes?.let { total ->
        usedBytes?.let { (total - it).coerceIn(0, total) }
    }

    fun encode(): String = JSONObject()
        .put("name", name)
        .put("upload", uploadBytes ?: JSONObject.NULL)
        .put("download", downloadBytes ?: JSONObject.NULL)
        .put("total", totalBytes ?: JSONObject.NULL)
        .put("expires", expiresAtSeconds ?: JSONObject.NULL)
        .put("neverExpires", neverExpires)
        .put("unlimited", unlimited)
        .toString()

    companion object {
        fun decode(raw: String?): SubscriptionUsage? = runCatching {
            val json = JSONObject(raw ?: return null)
            SubscriptionUsage(
                name = json.optString("name"),
                uploadBytes = json.optLong("upload").takeUnless { json.isNull("upload") },
                downloadBytes = json.optLong("download").takeUnless { json.isNull("download") },
                totalBytes = json.optLong("total").takeUnless { json.isNull("total") },
                expiresAtSeconds = json.optLong("expires").takeUnless { json.isNull("expires") },
                neverExpires = json.optBoolean("neverExpires"),
                unlimited = json.optBoolean("unlimited"),
            )
        }.getOrNull()
    }
}

/** Small cache shared with the home-screen widget. */
object SubscriptionUsageStore {
    private const val PREFS = "connecso_subscription_usage"
    private const val SNAPSHOT = "snapshot"
    private const val COUNTRY = "country"

    fun current(context: Context): SubscriptionUsage? = SubscriptionUsage.decode(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SNAPSHOT, null),
    )

    fun save(context: Context, usage: SubscriptionUsage?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(SNAPSHOT, usage?.encode()).apply()
    }

    fun saveCountry(context: Context, country: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(COUNTRY, country).apply()
    }

    fun country(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(COUNTRY, null).orEmpty()

    /** Fetch the active subscription once; this reads response metadata, not traffic estimates. */
    fun fetch(source: ChainSource, proxyPort: Int?): SubscriptionUsage {
        val uri = URL(source.url)
        require(uri.protocol == "https" || uri.protocol == "http") { "Subscription URL must use HTTP or HTTPS" }
        val proxy = proxyPort?.takeIf { it in 1..65535 }?.let {
            Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", it))
        } ?: Proxy.NO_PROXY
        val connection = (uri.openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = 9_000
            readTimeout = 9_000
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", "Connecso-Aether-Mobile")
            setRequestProperty("Accept", "text/plain, application/octet-stream, */*")
        }
        try {
            val code = connection.responseCode
            val headerInfo = connection.getHeaderField("subscription-userinfo")
                ?: connection.getHeaderField("Subscription-Userinfo")
            val body = runCatching {
                val stream = if (code >= 400) connection.errorStream else connection.inputStream
                stream?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var remaining = 1_048_576
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                    output.toString(Charsets.UTF_8.name())
                }.orEmpty()
            }.getOrDefault("")
            val metadata = parse(headerInfo ?: directive(body))
            return metadata.copy(name = source.name)
        } finally {
            connection.disconnect()
        }
    }

    private fun directive(body: String): String? {
        val match = Regex("(?im)^\\s*#subscription-userinfo\\s*:\\s*(.*?)\\s*$").find(body)
        if (match != null) return match.groupValues[1]
        val compact = body.trim().removePrefix("\uFEFF").filterNot(Char::isWhitespace)
        if (compact.isEmpty()) return null
        return runCatching {
            val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
            val decoded = Base64.getDecoder().decode(padded).toString(Charsets.UTF_8)
            Regex("(?im)^\\s*#subscription-userinfo\\s*:\\s*(.*?)\\s*$")
                .find(decoded)?.groupValues?.get(1)
        }.getOrNull()
    }

    private fun parse(raw: String?): SubscriptionUsage {
        val values = raw.orEmpty().split(';').mapNotNull { part ->
            val split = part.indexOf('=')
            if (split <= 0) null else part.substring(0, split).trim().lowercase() to
                part.substring(split + 1).trim()
        }.toMap()
        fun number(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
            values[key]?.toLongOrNull()?.takeIf { it >= 0 }
        }
        val total = number("total", "x-total")
        val expiry = number("expire", "x-expire")
        return SubscriptionUsage(
            name = "",
            uploadBytes = number("upload", "x-upload"),
            downloadBytes = number("download", "x-download"),
            totalBytes = total,
            expiresAtSeconds = expiry?.takeIf { it > 0 },
            neverExpires = expiry == 0L,
            unlimited = total == 0L,
        )
    }
}
