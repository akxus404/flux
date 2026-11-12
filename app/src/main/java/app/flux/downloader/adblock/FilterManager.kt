package app.flux.downloader.adblock

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import app.flux.downloader.browser.AdBlocker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Descarga y mantiene al día las listas de filtros (EasyList, uBlock, etc.).
 * Mientras no haya listas descargadas se usa una lista semilla incluida en la app.
 */
object FilterManager {

    class FilterList(val id: String, val name: String, val url: String, val defaultOn: Boolean)

    data class ListState(
        val id: String,
        val name: String,
        val enabled: Boolean,
        val rules: Int,
        val updatedAt: Long,
        val error: String?,
    )

    val catalog = listOf(
        FilterList("easylist", "EasyList · anuncios", "https://easylist.to/easylist/easylist.txt", true),
        FilterList("easyprivacy", "EasyPrivacy · rastreadores", "https://easylist.to/easylist/easyprivacy.txt", true),
        FilterList("ublock", "uBlock filters · anuncios y molestias", "https://ublockorigin.github.io/uAssets/filters/filters.txt", true),
        FilterList("badware", "uBlock · sitios maliciosos", "https://ublockorigin.github.io/uAssets/filters/badware.txt", true),
        FilterList(
            "peterlowe", "Peter Lowe · servidores de anuncios",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext", true,
        ),
        FilterList("annoyance", "Fanboy · molestias y avisos de cookies", "https://secure.fanboy.co.nz/fanboy-annoyance.txt", true),
        FilterList("easylist-es", "EasyList Español", "https://easylist-downloads.adblockplus.org/easylistspanish.txt", true),
    )

    @Volatile
    var engine: FilterEngine = FilterEngine.empty()
        private set

    private val _states = MutableStateFlow<List<ListState>>(emptyList())
    val states: StateFlow<List<ListState>> = _states.asStateFlow()

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private lateinit var app: Application
    private lateinit var sp: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val errors = ConcurrentHashMap<String, String>()
    private var lastCounts: Map<String, Int> = emptyMap()

    private const val STALE_MS = 72L * 60 * 60 * 1000

    private val dir get() = File(app.filesDir, "filters")
    private fun file(id: String) = File(dir, "$id.txt")
    private fun isEnabled(l: FilterList) = sp.getBoolean("on_${l.id}", l.defaultOn)
    private fun updatedAt(id: String) = sp.getLong("ts_$id", 0L)

    fun init(application: Application) {
        app = application
        sp = app.getSharedPreferences("filters", Context.MODE_PRIVATE)
        dir.mkdirs()
        engine = buildSeedOnly()
        publish(emptyMap())
        scope.launch {
            rebuild()
            val now = System.currentTimeMillis()
            val stale = catalog.any { isEnabled(it) && (!file(it.id).exists() || now - updatedAt(it.id) > STALE_MS) }
            if (stale) updateAll()
        }
    }

    fun setEnabled(id: String, on: Boolean) {
        sp.edit().putBoolean("on_$id", on).apply()
        publish(lastCounts)
        scope.launch {
            val l = catalog.firstOrNull { it.id == id } ?: return@launch
            if (on && !file(id).exists()) {
                _busy.value = true
                download(l)
                _busy.value = false
            }
            rebuild()
        }
    }

    fun updateNow() {
        scope.launch { updateAll() }
    }

    private fun buildSeedOnly(): FilterEngine {
        val b = FilterEngine.Builder()
        AdBlocker.seedHosts.forEach { b.addLine("||$it^") }
        return b.build()
    }

    private suspend fun updateAll() {
        if (_busy.value) return
        _busy.value = true
        try {
            for (l in catalog) if (isEnabled(l)) download(l)
            rebuild()
        } finally {
            _busy.value = false
        }
    }

    private fun download(l: FilterList) {
        try {
            val conn = (URL(l.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "Flux/1.0")
            }
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val tmp = File(dir, "${l.id}.tmp")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (tmp.length() < 200) throw IOException("lista vacía")
            tmp.copyTo(file(l.id), overwrite = true)
            tmp.delete()
            sp.edit().putLong("ts_${l.id}", System.currentTimeMillis()).apply()
            errors.remove(l.id)
        } catch (e: Exception) {
            errors[l.id] = e.message ?: "error de red"
        }
    }

    private suspend fun rebuild() = mutex.withLock {
        val b = FilterEngine.Builder()
        AdBlocker.seedHosts.forEach { b.addLine("||$it^") }
        val counts = HashMap<String, Int>()
        for (l in catalog) {
            if (!isEnabled(l)) continue
            val f = file(l.id)
            if (!f.exists()) continue
            val before = b.ruleCount
            runCatching { f.bufferedReader().useLines { lines -> lines.forEach { b.addLine(it) } } }
            counts[l.id] = b.ruleCount - before
        }
        engine = b.build()
        lastCounts = counts
        _version.value = _version.value + 1
        publish(counts)
    }

    private fun publish(counts: Map<String, Int>) {
        _states.value = catalog.map {
            ListState(it.id, it.name, isEnabled(it), counts[it.id] ?: 0, updatedAt(it.id), errors[it.id])
        }
    }
}
