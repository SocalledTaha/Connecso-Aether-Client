package com.whitedns.whiteaesther.data

import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * An app the user could plausibly want routed, or kept out of the tunnel.
 *
 * Icons are kept in a separate bitmap cache so app metadata remains a small,
 * stable value that can be enumerated quickly off the main thread.
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    /** Shipped with the device rather than installed by the user. */
    val system: Boolean,
)

object InstalledApps {
    private val lock = Any()
    private var appCache: List<InstalledApp>? = null
    private val iconCache = mutableMapOf<String, Bitmap>()
    private val mutableRevision = MutableStateFlow(0)
    val revision: StateFlow<Int> = mutableRevision
    private var packageReceiver: BroadcastReceiver? = null

    /** Keep app and icon caches warm across visits; invalidate on package changes. */
    fun registerPackageChanges(context: Context) {
        synchronized(lock) {
            if (packageReceiver != null) return
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) = invalidate()
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.applicationContext.registerReceiver(receiver, filter)
            }
            packageReceiver = receiver
        }
    }

    private fun invalidate() = synchronized(lock) {
        appCache = null
        iconCache.clear()
        mutableRevision.value = mutableRevision.value + 1
    }

    /**
     * Everything with a launcher entry, by name.
     *
     * Not every installed package: `QUERY_ALL_PACKAGES` is a restricted
     * permission on Play and would return several hundred services and
     * providers, none of which the user recognises or would knowingly route. A
     * launcher entry is a good proxy for "an app the user thinks of as an app".
     *
     * Touches the package manager once per entry, so it belongs off the main
     * thread on a phone with a lot of apps installed.
     *
     * Sorted by name, user-installed first: the apps somebody wants to exclude
     * are almost always ones they installed themselves.
     */
    fun launchable(context: Context): List<InstalledApp> {
        synchronized(lock) { appCache?.let { return it } }
        val manager = context.packageManager
        val loaded = runCatching {
            sequenceOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)
                .flatMap { category ->
                    manager.queryIntentActivities(
                        Intent(Intent.ACTION_MAIN).addCategory(category),
                        0,
                    ).asSequence()
                }
                .mapNotNull { it.activityInfo?.applicationInfo }
                // A package can contribute more than one launcher entry.
                .distinctBy { it.packageName }
                .map { info ->
                    InstalledApp(
                        packageName = info.packageName,
                        label = runCatching { manager.getApplicationLabel(info).toString() }
                            .getOrDefault(info.packageName),
                        system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    )
                }
                .sortedWith(compareBy({ it.system }, { it.label.lowercase() }))
                .toList()
        }.getOrDefault(emptyList())
        synchronized(lock) {
            appCache?.let { return it }
            appCache = loaded
        }
        return loaded
    }

    /** One app's launcher icon. Expensive enough to be worth doing off the main thread. */
    fun icon(context: Context, packageName: String): Drawable? =
        runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()

    /** A small raster icon cached by package and requested size. */
    fun iconBitmap(context: Context, packageName: String, sizePx: Int): Bitmap? {
        val key = "$packageName@$sizePx"
        synchronized(lock) { iconCache[key]?.let { return it } }
        val bitmap = runCatching {
            context.packageManager.getApplicationIcon(packageName).toBitmap(sizePx, sizePx)
        }.getOrNull() ?: return null
        synchronized(lock) { iconCache.putIfAbsent(key, bitmap)?.let { return it } }
        return bitmap
    }

    /** Warm the icons before the user reaches them while scrolling. */
    fun preloadIcons(context: Context, apps: List<InstalledApp>, sizePx: Int) {
        apps.forEach { iconBitmap(context, it.packageName, sizePx) }
    }
}
