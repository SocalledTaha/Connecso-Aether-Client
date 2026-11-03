package com.whitedns.whiteaesther.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.text.BidiFormatter
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import com.whitedns.whiteaesther.MainActivity
import com.whitedns.whiteaesther.R
import java.util.Locale

/** The live connection card shown in Android's notification shade. */
object AetherNotification {
    const val CHANNEL_ID = "aether_connection"
    const val NOTIFICATION_ID = 1819

    data class State(
        val title: String,
        val message: String,
        val country: String = "",
        val upload: String = "0 B/s",
        val download: String = "0 B/s",
        val ping: String = "—",
        val connected: Boolean = false,
    )

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.connection_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.connection_channel_description)
                setShowBadge(false)
            },
        )
    }

    fun build(context: Context, state: State): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            context,
            1,
            AetherVpnService.stopIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pingIntent = PendingIntent.getService(
            context,
            2,
            AetherVpnService.pingIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val colors = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val textColor = if (colors == Configuration.UI_MODE_NIGHT_YES) 0xFFFFFFFF.toInt() else 0xFF17212B.toInt()
        // Country names can be Persian while the notification row is laid out
        // left-to-right. Isolate the label so mixed-script names (flag + RTL
        // country + Latin WARP) remain whole instead of being clipped at the
        // wrong edge by RemoteViews' bidi handling.
        val routeLabel = directional(context, countryLabel(context, state.country.ifBlank { state.title }))
        val compact = RemoteViews(context.packageName, R.layout.notification_compact).apply {
            setTextViewText(R.id.notification_compact_country, routeLabel)
            setTextViewText(R.id.notification_compact_upload, directional(context, "↑${compactRate(state.upload)}"))
            setTextViewText(R.id.notification_compact_download, directional(context, "↓${compactRate(state.download)}"))
            setTextViewText(R.id.notification_compact_ping, directional(context, state.ping.replace(" ", "")))
            setImageViewResource(R.id.notification_compact_ping_button, R.drawable.ic_notification_ping)
            setImageViewResource(R.id.notification_compact_disconnect_button, R.drawable.ic_notification_disconnect)
            setOnClickPendingIntent(R.id.notification_compact_ping_button, pingIntent)
            setOnClickPendingIntent(R.id.notification_compact_disconnect_button, stopIntent)
            setViewVisibility(R.id.notification_compact_ping_button, if (state.connected) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.notification_compact_disconnect_button, if (state.connected) View.VISIBLE else View.GONE)
            setTextColor(R.id.notification_compact_country, textColor)
            setTextColor(R.id.notification_compact_upload, textColor)
            setTextColor(R.id.notification_compact_download, textColor)
            setTextColor(R.id.notification_compact_ping, textColor)
        }
        val expanded = RemoteViews(context.packageName, R.layout.notification_expanded).apply {
            setTextViewText(R.id.notification_expanded_title, routeLabel)
            setTextViewText(
                R.id.notification_expanded_details,
                directional(context, "↑ ${state.upload}    ↓ ${state.download}    ⚡ ${state.ping}"),
            )
            setImageViewResource(R.id.notification_ping_icon, R.drawable.ic_notification_ping)
            setImageViewResource(R.id.notification_disconnect_icon, R.drawable.ic_notification_disconnect)
            setTextViewText(R.id.notification_ping_label, context.getString(R.string.notification_ping))
            setTextViewText(R.id.notification_disconnect_label, context.getString(R.string.notification_disconnect))
            setOnClickPendingIntent(R.id.notification_ping_button, pingIntent)
            setOnClickPendingIntent(R.id.notification_disconnect_button, stopIntent)
            setViewVisibility(R.id.notification_actions, if (state.connected) View.VISIBLE else View.GONE)
            setTextColor(R.id.notification_expanded_title, textColor)
            setTextColor(R.id.notification_expanded_details, textColor)
            setTextColor(R.id.notification_ping_label, textColor)
            setTextColor(R.id.notification_disconnect_label, textColor)
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(context.getDrawable(R.mipmap.ic_launcher)?.toBitmap(64, 64))
            .setColor(0xFF149BDD.toInt())
            .setContentTitle(state.country.ifBlank { state.title })
            .setContentText(state.message.takeIf { !state.connected })
            .setContentIntent(openIntent)
            .setCustomContentView(compact)
            .setCustomBigContentView(expanded)
            .setCustomHeadsUpContentView(compact)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setWhen(0)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /** Turn a country code/name in a node label into the flag + readable name. */
    private fun countryLabel(context: Context, raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return value
        if (value.equals("DIRECT", ignoreCase = true) ||
            value.equals("aether", ignoreCase = true) ||
            value.equals("warp", ignoreCase = true) ||
            value.equals("اتر", ignoreCase = true)
        ) {
            // A short, language-neutral label avoids clipping mixed RTL/LTR
            // text in Android's compact notification RemoteViews.
            return "WARP"
        }
        // Some subscription names already include a flag. Keep their provider
        // formatting instead of duplicating the emoji.
        if (value.codePoints().anyMatch { it in 0x1F1E6..0x1F1FF }) return value

        val locale = context.resources.configuration.locales[0] ?: Locale.ENGLISH
        val countryCode = if (value.matches(Regex("[A-Za-z]{2}"))) {
            value.uppercase(Locale.ROOT).takeIf { it in Locale.getISOCountries() }
        } else {
            Locale.getISOCountries().firstOrNull { code ->
                val englishName = Locale("", code).getDisplayCountry(Locale.ENGLISH)
                englishName.isNotBlank() && Regex(
                    "(?i)(?<![\\p{L}])${Regex.escape(englishName)}(?![\\p{L}])",
                ).containsMatchIn(value)
            } ?: Locale.getISOCountries().firstOrNull { code ->
                Regex("(?i)(?<![A-Za-z])${Regex.escape(code)}(?![A-Za-z])").containsMatchIn(value)
            }
        } ?: return value

        val name = Locale("", countryCode).getDisplayCountry(locale).ifBlank {
            Locale("", countryCode).getDisplayCountry(Locale.ENGLISH)
        }
        val flag = countryCode.map { character ->
            String(Character.toChars(0x1F1E6 + character.uppercaseChar().code - 'A'.code))
        }.joinToString("")
        return "$flag  $name"
    }

    /** Keep numeric rates and units in visual order in both UI languages. */
    private fun directional(context: Context, value: String): CharSequence =
        BidiFormatter.getInstance(context.resources.configuration.locales[0]).unicodeWrap(value)

    /**
     * The compact shade row is narrow. Promote three-digit values into the
     * next unit and keep at most two decimals, matching the traffic display
     * users asked for without truncating the ping beside it.
     */
    private fun compactRate(value: String): String {
        val match = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*(B|KB|MB|GB|TB)/s").find(value)
            ?: return value.replace(" ", "")
        var amount = match.groupValues[1].toDoubleOrNull() ?: return value.replace(" ", "")
        val units = listOf("K/s", "M/s", "G/s", "T/s")
        var unit = when (match.groupValues[2]) {
            "B" -> -1
            "KB" -> 0
            "MB" -> 1
            "GB" -> 2
            "TB" -> 3
            else -> return value.replace(" ", "")
        }
        if (unit == -1) {
            amount /= 1024.0
            unit = 0
        }
        while (amount >= 100 && unit < units.lastIndex) {
            amount /= 1024.0
            unit++
        }
        val number = String.format(Locale.ROOT, "%.2f", amount).trimEnd('0').trimEnd('.')
        return "$number${units[unit]}"
    }
}
