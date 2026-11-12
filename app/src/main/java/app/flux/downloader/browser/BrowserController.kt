package app.flux.downloader.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.flux.downloader.adblock.FilterManager
import app.flux.downloader.adblock.ResourceType
import app.flux.downloader.engine.FileDownload
import app.flux.downloader.engine.FileDownloads
import app.flux.downloader.engine.SiteGuard
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Una pestaña: su estado se guarda como Bundle y se restaura al volver a ella. */
class BrowserTab {
    var title by mutableStateOf("Nueva pestaña")
    var url by mutableStateOf<String?>(null)
    var state: Bundle? = null
}

/** Estado del navegador que sobrevive al cambio de pestaña de la app. */
class BrowserSession {
    val tabs = mutableStateListOf(BrowserTab())
    var activeIndex by mutableIntStateOf(0)
}

/**
 * Navegador endurecido sobre WebView.
 * La política de seguridad vive aquí: HTTPS obligatorio, esquemas peligrosos, popups,
 * redirecciones automáticas, permisos y bloqueo de anuncios/rastreadores con listas completas.
 */
class BrowserController(
    private val ctx: Context,
    private val prefs: BrowserPrefs,
    private val store: BrowserStore,
    private val session: BrowserSession,
) {

    var webView: WebView? = null

    // ---- Estado observable por la UI ----
    var currentUrl by mutableStateOf<String?>(null)
    var pageTitle by mutableStateOf("")
    var progress by mutableFloatStateOf(0f)
    var loading by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var showStart by mutableStateOf(true)
    var message by mutableStateOf<String?>(null)
    var blockedCount by mutableIntStateOf(0)
    var totalBlocked by mutableLongStateOf(prefs.totalBlocked)
    var pendingFile by mutableStateOf<FileDownload?>(null)

    var blockAds by mutableStateOf(prefs.blockAds)
        private set
    var blockRedirects by mutableStateOf(prefs.blockRedirects)
        private set
    var desktopMode by mutableStateOf(prefs.desktopMode)
        private set
    var siteProtected by mutableStateOf(true)
        private set

    val sniffed = mutableStateListOf<String>()
    val bookmarks = mutableStateListOf<Entry>().also { it.addAll(store.loadBookmarks()) }
    val history = mutableStateListOf<Entry>().also { it.addAll(store.loadHistory()) }

    val tabs get() = session.tabs
    val activeIndex get() = session.activeIndex

    var onDownloadRequest: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val active: BrowserTab get() = session.tabs[session.activeIndex.coerceIn(0, session.tabs.lastIndex)]

    @Volatile private var pageHost: String? = null
    @Volatile private var siteAllowed = false
    private var pageFinished = false
    private var upgradeCount = 0
    private var mobileUa = ""
    private var docStartHandler: ScriptHandler? = null
    private var docStartSupported = true

    private val mediaRegex = Regex("""\.(m3u8|mpd|mp4|webm|m4a|mp3)(\?|$)""", RegexOption.IGNORE_CASE)
    private val desktopUa =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // ------------------------------------------------------------------
    // Creación del WebView endurecido
    // ------------------------------------------------------------------
    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    fun createWebView(context: Context): WebView {
        val wv = WebView(context)
        webView = wv

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setAllowFileAccessFromFileURLs(false)
            setAllowUniversalAccessFromFileURLs(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSafeBrowsingEnabled(true)
            setGeolocationEnabled(false)
            setSaveFormData(false)
            mediaPlaybackRequiresUserGesture = true
            userAgentString = userAgentString
                .replace("; wv", "")
                .replace(Regex("Version/\\d+\\.\\d+ "), "")
            mobileUa = userAgentString
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, false)
        }

        wv.webViewClient = SecureClient()
        wv.webChromeClient = SecureChrome()
        wv.setDownloadListener { url, ua, cd, mime, _ -> handleDownload(url, ua, cd, mime) }

        applyUa()
        applyGenericCosmetics()
        restoreActive()
        return wv
    }

    // ------------------------------------------------------------------
    // Navegación
    // ------------------------------------------------------------------
    fun go(input: String) {
        val target = toUrl(input) ?: return
        showStart = false
        load(target)
    }

    fun back() {
        if (webView?.canGoBack() == true) webView?.goBack() else home()
    }

    fun forward() { webView?.goForward() }
    fun stop() { webView?.stopLoading(); loading = false }
    fun reload() { currentUrl?.let { load(it) } ?: webView?.reload() }

    fun home() {
        webView?.stopLoading()
        webView?.loadUrl("about:blank")
        showStart = true
        currentUrl = null
        pageTitle = ""
        active.url = null
        active.title = "Nueva pestaña"
        active.state = null
        setPage(null)
    }

    // ------------------------------------------------------------------
    // Pestañas
    // ------------------------------------------------------------------
    fun saveActive() {
        val t = active
        if (showStart) {
            t.url = null
            t.state = null
            return
        }
        webView?.let { wv ->
            val b = Bundle()
            wv.saveState(b)
            t.state = b
        }
        t.url = currentUrl
        t.title = pageTitle.ifBlank { currentUrl.orEmpty() }
    }

    fun restoreActive() {
        val t = active
        showStart = t.url == null
        currentUrl = t.url
        pageTitle = if (showStart) "" else t.title
        setPage(t.url)
        sniffed.clear()
        val wv = webView ?: return
        val st = t.state
        val u = t.url
        when {
            st != null -> if (wv.restoreState(st) == null && u != null) load(u)
            u != null -> load(u)
            else -> wv.loadUrl("about:blank")
        }
    }

    fun switchTo(i: Int) {
        if (i == session.activeIndex || i !in session.tabs.indices) return
        saveActive()
        session.activeIndex = i
        restoreActive()
    }

    fun newTab() {
        saveActive()
        session.tabs.add(BrowserTab())
        session.activeIndex = session.tabs.lastIndex
        restoreActive()
    }

    fun closeTab(i: Int) {
        if (i !in session.tabs.indices) return
        if (session.tabs.size == 1) {
            session.tabs[0].apply { url = null; title = "Nueva pestaña"; state = null }
            session.activeIndex = 0
            restoreActive()
            return
        }
        val wasActive = i == session.activeIndex
        session.tabs.removeAt(i)
        if (i < session.activeIndex) {
            session.activeIndex--
        } else if (wasActive) {
            session.activeIndex = session.activeIndex.coerceAtMost(session.tabs.lastIndex)
            restoreActive()
        }
    }

    // ------------------------------------------------------------------
    // Ajustes y privacidad
    // ------------------------------------------------------------------
    fun setBlockAds(v: Boolean) {
        blockAds = v
        prefs.blockAds = v
        applyGenericCosmetics()
    }

    fun setBlockRedirects(v: Boolean) { blockRedirects = v; prefs.blockRedirects = v }

    fun setSiteProtected(on: Boolean) {
        val h = pageHost ?: return
        prefs.setSiteProtected(h, on)
        setPage(currentUrl)
        applyGenericCosmetics()
        reload()
    }

    fun toggleDesktop() {
        desktopMode = !desktopMode
        prefs.desktopMode = desktopMode
        applyUa()
        reload()
    }

    fun clearData() {
        webView?.apply { clearCache(true); clearHistory() }
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        say("Datos de navegación borrados")
    }

    /** Botón de pánico: borra datos, historial y cierra todas las pestañas. */
    fun panic() {
        clearData()
        clearHistory()
        session.tabs.clear()
        session.tabs.add(BrowserTab())
        session.activeIndex = 0
        restoreActive()
        say("Todo borrado")
    }

    // ------------------------------------------------------------------
    // Marcadores e historial
    // ------------------------------------------------------------------
    fun isBookmarked(): Boolean = currentUrl?.let { u -> bookmarks.any { it.url == u } } == true

    fun toggleBookmark() {
        val u = currentUrl ?: return
        val existing = bookmarks.firstOrNull { it.url == u }
        if (existing != null) bookmarks.remove(existing)
        else bookmarks.add(0, Entry(pageTitle.ifBlank { u }, u, System.currentTimeMillis()))
        store.saveBookmarks(bookmarks)
    }

    fun removeBookmark(e: Entry) {
        bookmarks.remove(e)
        store.saveBookmarks(bookmarks)
    }

    fun clearHistory() {
        history.clear()
        store.saveHistory(history)
    }

    private fun addHistory(url: String, title: String) {
        if (history.firstOrNull()?.url == url) return
        history.add(0, Entry(title.ifBlank { url }, url, System.currentTimeMillis()))
        while (history.size > 500) history.removeAt(history.lastIndex)
        store.saveHistory(history)
    }

    // ------------------------------------------------------------------
    // Descargas de archivos
    // ------------------------------------------------------------------
    fun confirmPendingFile() {
        pendingFile?.let(::startFile)
        pendingFile = null
    }

    private fun startFile(f: FileDownload) {
        say(if (FileDownloads.enqueue(ctx, f)) "Descargando archivo…" else "No se pudo iniciar la descarga")
    }

    private fun handleDownload(url: String, ua: String?, cd: String?, mime: String?) {
        // Única restricción: YouTube
        if (SiteGuard.isBlocked(url)) {
            say("Este sitio no es compatible con Flux")
            return
        }
        val isMedia = mime?.startsWith("video/") == true || mime?.startsWith("audio/") == true ||
            mediaRegex.containsMatchIn(url)
        if (isMedia) {
            ui { onDownloadRequest?.invoke(url) }
            return
        }
        val file = FileDownload(url, ua, cd, mime)
        if (FileDownloads.isRisky(file)) ui { pendingFile = file } else startFile(file)
    }

    // ------------------------------------------------------------------
    // Utilidades internas
    // ------------------------------------------------------------------
    private fun load(url: String) {
        pageFinished = false
        webView?.loadUrl(url, mapOf("Sec-GPC" to "1", "DNT" to "1"))
    }

    private fun toUrl(input: String): String? {
        val t = input.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("https://", true)) return t
        if (t.startsWith("http://", true)) return "https://" + t.substring(7)
        val looksLikeHost = !t.contains(' ') && t.contains('.') && !t.startsWith(".") && !t.contains(':')
        return if (looksLikeHost) "https://$t" else "https://duckduckgo.com/?q=" + Uri.encode(t)
    }

    private fun applyUa() {
        webView?.settings?.apply {
            userAgentString = if (desktopMode) desktopUa else mobileUa
            useWideViewPort = desktopMode
            loadWithOverviewMode = desktopMode
        }
    }

    private fun setPage(url: String?) {
        val h = url?.let { runCatching { Uri.parse(it).host?.lowercase() }.getOrNull() }
        pageHost = h
        siteAllowed = h != null && prefs.allowedSites.any { h == it || h.endsWith(".$it") }
        siteProtected = !siteAllowed
    }

    private fun say(msg: String) { main.post { message = msg } }
    private fun ui(block: () -> Unit) { main.post(block) }

    private fun refreshNav(view: WebView) {
        canGoBack = view.canGoBack()
        canGoForward = view.canGoForward()
    }

    private fun showError(view: WebView, title: String, detail: String) {
        val html = """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body style="background:#0A0A0A;color:#fff;font-family:sans-serif;padding:32px;text-align:center">
            <h2 style="color:#FF6A00">${TextUtils.htmlEncode(title)}</h2>
            <p style="color:#B5B5B5">${TextUtils.htmlEncode(detail)}</p></body></html>
        """.trimIndent()
        view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private fun sniff(url: String) {
        if (!mediaRegex.containsMatchIn(url) || SiteGuard.isBlocked(url)) return
        ui { if (url !in sniffed && sniffed.size < 10) sniffed.add(url) }
    }

    /** WebView no informa el tipo de recurso: se deduce por extensión y cabecera Accept. */
    private fun inferType(req: WebResourceRequest): Int {
        val path = req.url.path?.lowercase().orEmpty()
        when (path.substringAfterLast('.', "")) {
            "js", "mjs" -> return ResourceType.SCRIPT
            "css" -> return ResourceType.CSS
            "png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico", "bmp" -> return ResourceType.IMAGE
            "woff", "woff2", "ttf", "otf", "eot" -> return ResourceType.FONT
            "mp4", "webm", "m4s", "mp3", "m4a", "ogg", "m3u8", "mpd", "ts" -> return ResourceType.MEDIA
        }
        val accept = req.requestHeaders["Accept"].orEmpty()
        return when {
            accept.contains("text/css") -> ResourceType.CSS
            accept.contains("text/html") -> ResourceType.SUBDOC
            accept.contains("image/") -> ResourceType.IMAGE
            else -> ResourceType.UNKNOWN
        }
    }

    // ------------------------------------------------------------------
    // Filtrado cosmético (ocultar huecos y banners que quedan tras bloquear la red)
    // ------------------------------------------------------------------
    private fun styleScript(id: String, css: String): String =
        "(function(){try{var s=document.getElementById('$id');if(!s){s=document.createElement('style');" +
            "s.id='$id';(document.head||document.documentElement).appendChild(s);}" +
            "s.textContent=${JSONObject.quote(css)};}catch(e){}})();"

    /** CSS genérico: se inyecta antes de que cargue la página, para que no se vea parpadear. */
    fun applyGenericCosmetics() {
        val wv = webView ?: return
        docStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (!docStartSupported) return
        docStartHandler?.remove()
        docStartHandler = null
        if (!blockAds) return
        val css = FilterManager.engine.genericCss
        if (css.isEmpty()) return
        val allowed = JSONArray(prefs.allowedSites.toList()).toString()
        val script = "(function(){try{if($allowed.indexOf(location.hostname)>=0)return;" +
            "function f(){var s=document.createElement('style');s.id='__flux_g';" +
            "s.textContent=${JSONObject.quote(css)};(document.head||document.documentElement).appendChild(s);}" +
            "if(document.documentElement)f();else document.addEventListener('DOMContentLoaded',f);}catch(e){}})();"
        docStartHandler = WebViewCompat.addDocumentStartJavaScript(wv, script, setOf("*"))
    }

    private fun applySiteCosmetics(view: WebView) {
        if (!blockAds || siteAllowed) return
        val host = pageHost ?: return
        val engine = FilterManager.engine
        val css = engine.cssForHost(host) + if (docStartSupported) "" else engine.genericCss
        if (css.isNotEmpty()) view.evaluateJavascript(styleScript("__flux_d", css), null)
    }

    // ------------------------------------------------------------------
    // WebViewClient: política de seguridad
    // ------------------------------------------------------------------
    private inner class SecureClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase()

            if (scheme == "about") return false

            // 1) HTTPS obligatorio: se sube de http a https
            if (scheme == "http") {
                if (request.isForMainFrame) {
                    if (++upgradeCount > 5) {
                        upgradeCount = 0
                        showError(view, "No se pudo conectar de forma segura", "Este sitio no ofrece HTTPS.")
                    } else {
                        val https = uri.buildUpon().scheme("https").build().toString()
                        ui { load(https) }
                    }
                }
                return true
            }

            // 2) Otros esquemas (intent://, market://, file://, javascript:…) bloqueados
            if (scheme != "https") {
                if (request.isForMainFrame) say("Se bloqueó un intento de abrir otra app o archivo")
                return true
            }

            // 3) Redirecciones automáticas sin gesto del usuario
            if (blockRedirects && request.isForMainFrame && pageFinished &&
                !request.hasGesture() && !request.isRedirect
            ) {
                val from = pageHost
                if (from != null && from != uri.host) {
                    say("Redirección automática bloqueada")
                    return true
                }
            }
            return false
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val uri = request.url
            if (blockAds && !siteAllowed && !request.isForMainFrame) {
                val type = inferType(request)
                if (FilterManager.engine.shouldBlock(uri.toString(), pageHost, type)) {
                    ui {
                        blockedCount++
                        totalBlocked++
                    }
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
            }
            sniff(uri.toString())
            return null
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            pageFinished = false
            loading = true
            progress = 0f
            sniffed.clear()
            if (url != null && url.startsWith("https://")) {
                if (url != currentUrl) blockedCount = 0
                currentUrl = url
                active.url = url
                showStart = false
                setPage(url)
            }
            refreshNav(view)
            applySiteCosmetics(view)
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            applySiteCosmetics(view)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            loading = false
            pageFinished = true
            upgradeCount = 0
            refreshNav(view)
            prefs.totalBlocked = totalBlocked
            if (url != null && url.startsWith("https://") && !showStart) addHistory(url, pageTitle)
            applySiteCosmetics(view)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            refreshNav(view)
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            showError(view, "Conexión no segura", "El certificado de este sitio no es válido. Se canceló la carga para protegerte.")
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                showError(view, "No se pudo cargar la página", error.description?.toString().orEmpty())
            }
        }

        override fun onSafeBrowsingHit(
            view: WebView,
            request: WebResourceRequest,
            threatType: Int,
            callback: SafeBrowsingResponse,
        ) {
            callback.backToSafety(true)
            say("Sitio peligroso bloqueado por Navegación segura")
        }
    }

    // ------------------------------------------------------------------
    // WebChromeClient: permisos denegados por defecto
    // ------------------------------------------------------------------
    private inner class SecureChrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress = newProgress / 100f
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            pageTitle = title.orEmpty()
            if (!showStart && !title.isNullOrBlank()) active.title = title
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }
    }
}
