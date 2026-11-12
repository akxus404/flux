package app.flux.downloader.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Entry(val title: String, val url: String, val time: Long = 0L)

class BrowserStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("browser_store", Context.MODE_PRIVATE)

    fun loadBookmarks(): List<Entry> = load("bookmarks")
    fun loadHistory(): List<Entry> = load("history")
    fun saveBookmarks(list: List<Entry>) = save("bookmarks", list)
    fun saveHistory(list: List<Entry>) = save("history", list.take(500))

    private fun load(key: String): List<Entry> = runCatching {
        val a = JSONArray(sp.getString(key, "[]") ?: "[]")
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Entry(o.getString("t"), o.getString("u"), o.optLong("d", 0L))
        }
    }.getOrDefault(emptyList())

    private fun save(key: String, list: List<Entry>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("t", it.title).put("u", it.url).put("d", it.time)) }
        sp.edit().putString(key, a.toString()).apply()
    }
}
