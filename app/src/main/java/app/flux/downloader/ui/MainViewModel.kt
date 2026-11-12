package app.flux.downloader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.flux.downloader.browser.BrowserSession
import app.flux.downloader.engine.Choice
import app.flux.downloader.engine.DownloadItem
import app.flux.downloader.engine.DownloadManager
import app.flux.downloader.engine.Engine
import app.flux.downloader.engine.MediaInfo
import app.flux.downloader.engine.SiteGuard
import app.flux.downloader.engine.friendlyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _link = MutableStateFlow("")
    val link: StateFlow<String> = _link.asStateFlow()

    private val _analysis = MutableStateFlow<Analysis>(Analysis.Idle)
    val analysis: StateFlow<Analysis> = _analysis.asStateFlow()

    val downloads: StateFlow<List<DownloadItem>> = DownloadManager.downloads

    /** Estado del navegador (sobrevive al cambio de pestaña). */
    val browserSession = BrowserSession()

    fun onLinkChange(value: String) {
        _link.value = value
        if (_analysis.value !is Analysis.Loading) _analysis.value = Analysis.Idle
    }

    /** Texto pegado o compartido desde otra app: extrae el enlace y lo analiza. */
    fun onSharedText(text: String) {
        _link.value = SiteGuard.extractUrl(text) ?: text.trim()
        analyze()
    }

    fun analyze() {
        val url = SiteGuard.extractUrl(_link.value)
        if (url == null) {
            _analysis.value = Analysis.Error("Pega un enlace válido (que empiece por https://)")
            return
        }
        if (SiteGuard.isBlocked(url)) {
            _analysis.value = Analysis.Error("Este sitio no es compatible con Flux.")
            return
        }
        _analysis.value = Analysis.Loading
        viewModelScope.launch {
            _analysis.value = try {
                Analysis.Ready(Engine.analyze(url))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Analysis.Error(friendlyError(e))
            }
        }
    }

    fun startDownload(info: MediaInfo, choice: Choice) = DownloadManager.start(info, choice)
    fun cancel(id: String) = DownloadManager.cancel(id)
    fun remove(id: String) = DownloadManager.remove(id)
}
