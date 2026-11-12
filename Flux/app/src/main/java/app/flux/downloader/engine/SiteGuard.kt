package app.flux.downloader.engine

import java.net.URI

/** Validación de enlaces y lista de sitios que Flux NO soporta. */
object SiteGuard {

    // Decisión de producto: YouTube queda fuera.
    private val blockedHosts = listOf(
        "youtube.com",
        "youtu.be",
        "youtube-nocookie.com",
        "googlevideo.com",
    )

    private val urlRegex = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)

    /** Extrae el primer enlace de un texto (útil al compartir desde otras apps). */
    fun extractUrl(text: String): String? =
        urlRegex.find(text)?.value?.trim()?.trimEnd('.', ',', ')', ']')

    fun isBlocked(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
        return blockedHosts.any { host == it || host.endsWith(".$it") }
    }
}
