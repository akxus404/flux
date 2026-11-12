package app.flux.downloader.engine

fun friendlyError(e: Exception): String {
    val msg = e.message.orEmpty()
    return when {
        msg.contains("Unsupported URL", true) -> "Este enlace no es compatible o no contiene un video."
        msg.contains("private", true) || msg.contains("login", true) || msg.contains("sign in", true) ->
            "El contenido es privado o requiere iniciar sesión."
        msg.contains("Unable to resolve", true) || msg.contains("timed out", true) || msg.contains("network", true) ->
            "Error de red. Revisa tu conexión e inténtalo de nuevo."
        msg.contains("DRM", true) -> "Este contenido está protegido con DRM y no se puede descargar."
        else -> msg.lineSequence().lastOrNull { it.isNotBlank() }?.take(160) ?: "Ocurrió un error inesperado."
    }
}
