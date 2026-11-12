package app.flux.downloader.engine

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil

data class FileDownload(
    val url: String,
    val userAgent: String?,
    val contentDisposition: String?,
    val mime: String?,
)

/** Descarga de archivos cualquiera (PDF, ZIP, APK, etc.) mediante el gestor del sistema. */
object FileDownloads {

    private val risky = listOf(".apk", ".xapk", ".apks", ".exe", ".msi", ".bat", ".scr", ".jar", ".dex", ".sh", ".dmg", ".pkg")

    fun fileName(f: FileDownload): String = URLUtil.guessFileName(f.url, f.contentDisposition, f.mime)

    /** Los ejecutables piden confirmación; no se bloquean. */
    fun isRisky(f: FileDownload): Boolean {
        val name = fileName(f).lowercase()
        return f.mime == "application/vnd.android.package-archive" || risky.any { name.endsWith(it) }
    }

    @Suppress("DEPRECATION")
    fun enqueue(ctx: Context, f: FileDownload): Boolean = runCatching {
        val req = DownloadManager.Request(Uri.parse(f.url))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Flux/${fileName(f)}")
        f.mime?.let { req.setMimeType(it) }
        f.userAgent?.let { req.addRequestHeader("User-Agent", it) }
        CookieManager.getInstance().getCookie(f.url)?.let { req.addRequestHeader("Cookie", it) }
        ctx.getSystemService(DownloadManager::class.java).enqueue(req)
    }.isSuccess
}
