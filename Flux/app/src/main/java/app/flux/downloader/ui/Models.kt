package app.flux.downloader.ui

import app.flux.downloader.engine.MediaInfo

sealed interface Analysis {
    data object Idle : Analysis
    data object Loading : Analysis
    data class Ready(val info: MediaInfo) : Analysis
    data class Error(val message: String) : Analysis
}

fun formatDuration(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
