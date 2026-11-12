package app.flux.downloader.browser

import android.content.Context

class BrowserPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("browser", Context.MODE_PRIVATE)

    var blockAds: Boolean
        get() = sp.getBoolean("block_ads", true)
        set(v) = sp.edit().putBoolean("block_ads", v).apply()

    var blockRedirects: Boolean
        get() = sp.getBoolean("block_redirects", true)
        set(v) = sp.edit().putBoolean("block_redirects", v).apply()

    var desktopMode: Boolean
        get() = sp.getBoolean("desktop_mode", false)
        set(v) = sp.edit().putBoolean("desktop_mode", v).apply()

    var totalBlocked: Long
        get() = sp.getLong("total_blocked", 0L)
        set(v) = sp.edit().putLong("total_blocked", v).apply()

    /** Sitios en los que el usuario pausó la protección. */
    val allowedSites: Set<String>
        get() = sp.getStringSet("allowed_sites", emptySet())?.toSet() ?: emptySet()

    fun setSiteProtected(host: String, protectedOn: Boolean) {
        val cur = allowedSites.toMutableSet()
        if (protectedOn) cur.removeAll { host == it || host.endsWith(".$it") } else cur.add(host)
        sp.edit().putStringSet("allowed_sites", cur).apply()
    }
}
