package app.flux.downloader

import android.app.Application
import app.flux.downloader.adblock.FilterManager
import app.flux.downloader.engine.DownloadManager
import app.flux.downloader.engine.Engine

class FluxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DownloadManager.init(this)
        FilterManager.init(this)
        Engine.init(this)
    }
}
