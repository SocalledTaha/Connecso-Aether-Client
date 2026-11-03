package com.whitedns.whiteaesther.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Whether a newer release has been published.
 *
 * Only ever asked while the tunnel is up. The request itself says nothing
 * secret, but its destination does: a plain call to the GitHub API from an
 * Iranian address announces that this device runs a circumvention tool, to the
 * one party best placed to act on it. Inside the tunnel it is indistinguishable
 * from the rest of the session.
 */
object UpdateChecker {
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/${AppUpdatePolicy.REPOSITORY}/releases/latest"
    private const val RELEASES_PAGE =
        "https://github.com/${AppUpdatePolicy.REPOSITORY}/releases/latest"
    private const val TIMEOUT_MS = 8_000

    /** Background checks are throttled; a manual check always bypasses this. */
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val FORCED_CHECK_INTERVAL_MS = 5 * 60 * 1000L

    private val Context.updateStore by preferencesDataStore(name = "whiteaesther_updates")
    private val LAST_CHECKED = longPreferencesKey("last_checked")
    private val DISMISSED_VERSION = stringPreferencesKey("dismissed_version")

    data class Available(val version: String, val url: String = RELEASES_PAGE)

    sealed interface CheckResult {
        data class Update(val available: Available) : CheckResult
        data object UpToDate : CheckResult
        data object Failed : CheckResult
        data object Throttled : CheckResult
    }

    /**
     * Looks for a newer release, or returns null.
     *
     * Returns null for every uninteresting outcome -- checked recently, no
     * network, a malformed answer, already the newest, or a version the user
     * has already said no to. A checker that reported its own failures would
     * interrupt a session to say nothing.
     */
    suspend fun check(context: Context, currentVersion: String): Available? =
        when (val result = checkNow(context, currentVersion, force = false)) {
            is CheckResult.Update -> result.available
            else -> null
        }

    /** A user-triggered check can bypass the background cache immediately. */
    suspend fun checkNow(context: Context, currentVersion: String, force: Boolean): CheckResult {
        val store = context.updateStore
        val preferences = store.data.first()

        val now = System.currentTimeMillis()
        val last = preferences[LAST_CHECKED] ?: 0L
        // A clock moved backwards would otherwise lock checking out until the
        // original time came round again.
        val interval = if (force) FORCED_CHECK_INTERVAL_MS else CHECK_INTERVAL_MS
        if (last in 1..now && now - last < interval) return CheckResult.Throttled

        val latest = fetchLatestTag() ?: return CheckResult.Failed
        store.edit { it[LAST_CHECKED] = now }

        if (preferences[DISMISSED_VERSION] == latest) return CheckResult.UpToDate
        if (!isNewer(latest, currentVersion)) return CheckResult.UpToDate
        return CheckResult.Update(Available(latest))
    }

    /** Remembers that the user does not want to hear about this one again. */
    suspend fun dismiss(context: Context, version: String) {
        context.updateStore.edit { it[DISMISSED_VERSION] = version }
    }

    /**
     * Compares two dotted versions numerically.
     *
     * Not a string comparison: "1.10.0" sorts before "1.9.0" as text, and the
     * tenth release of a minor line is exactly when that would first be
     * noticed. Anything after the numbers -- a `-preview` suffix, a git
     * describe tail -- is ignored, so a development build off the current tag
     * is not offered an update to the release it already contains.
     */
    internal fun isNewer(candidate: String, current: String): Boolean {
        val left = numericParts(candidate)
        val right = numericParts(current)
        if (left.isEmpty() || right.isEmpty()) return false
        for (index in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrElse(index) { 0 }
            val b = right.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun numericParts(version: String): List<Int> =
        version.trim()
            .removePrefix("v")
            .takeWhile { it.isDigit() || it == '.' }
            .split('.')
            .mapNotNull { it.toIntOrNull() }

    private suspend fun fetchLatestTag(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "Connecso-Aether-Client")
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                val body = connection.inputStream.bufferedReader().readText()
                JSONObject(body).optString("tag_name").takeUnless(String::isBlank)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }
}
