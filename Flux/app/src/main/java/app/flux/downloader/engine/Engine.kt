package app.flux.downloader.engine

import android.app.Application
import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

data class MediaInfo(
    val url: String,
    val title: String,
    val uploader: String?,
    val thumbnail: String?,
    val durationSec: Long,
    val heights: List<Int>,
)

data class Choice(
    val audioOnly: Boolean,
    val height: Int?,
    val audioFormat: String = "mp3",
)

/** Envuelve yt-dlp + FFmpeg. Toda la lógica de descarga vive aquí. */
object Engine {

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun init(app: Application) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                _ready.value = true
                // Los sitios cambian a diario: actualizamos el extractor en segundo plano.
                runCatching { YoutubeDL.getInstance().updateYoutubeDL(app) }
            } catch (e: Exception) {
                _error.value = "No se pudo iniciar el motor: ${e.message}"
            }
        }
    }

    suspend fun awaitReady() {
        withTimeout(60_000) {
            while (!_ready.value) {
                _error.value?.let { error(it) }
                delay(150)
            }
        }
    }

    private fun Any?.asInt(): Int = this?.toString()?.toIntOrNull() ?: 0

    suspend fun analyze(url: String): MediaInfo {
        awaitReady()
        return withContext(Dispatchers.IO) {
            val request = YoutubeDLRequest(url).apply { addOption("--no-playlist") }
            val info = YoutubeDL.getInstance().getInfo(request)

            val heights = (info.formats ?: emptyList())
                .filter { f -> f.vcodec != null && f.vcodec != "none" && f.height.asInt() > 0 }
                .map { it.height.asInt() }
                .distinct()
                .sortedDescending()

            MediaInfo(
                url = url,
                title = info.title ?: "Video",
                uploader = info.uploader,
                thumbnail = info.thumbnail,
                durationSec = info.duration.asInt().toLong(),
                heights = heights,
            )
        }
    }

    /**
     * Descarga a una carpeta temporal y devuelve el archivo final.
     * [onProgress] recibe (porcentaje 0..100, segundos restantes).
     */
    suspend fun download(
        ctx: Context,
        url: String,
        choice: Choice,
        id: String,
        onProgress: (Float, Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        awaitReady()
        val dir = File(ctx.cacheDir, "dl/$id").apply { mkdirs() }

        val req = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("-o", "${dir.absolutePath}/%(title).80B.%(ext)s")
            addOption("--concurrent-fragments", "4")
            addOption("--retries", "10")
            addOption("--embed-metadata")

            if (choice.audioOnly) {
                addOption("-f", "bestaudio/best")
                addOption("-x")
                addOption("--audio-format", choice.audioFormat)
                addOption("--audio-quality", "0")
            } else {
                val h = choice.height
                val selector = if (h != null) "bv*[height<=$h]+ba/b[height<=$h]/bv*+ba/b" else "bv*+ba/b"
                addOption("-f", selector)
                // Prioriza resolución y, a igualdad, códecs compatibles con cualquier reproductor
                addOption("-S", "res,vcodec:h264,acodec:m4a")
                addOption("--merge-output-format", "mp4")
            }
        }

        YoutubeDL.getInstance().execute(req, id) { progress, eta, _ ->
            onProgress(progress, eta)
        }

        dir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.length() }
            ?: error("La descarga terminó pero no se generó ningún archivo")
    }

    fun cancel(id: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
    }

    fun cleanup(ctx: Context, id: String) {
        File(ctx.cacheDir, "dl/$id").deleteRecursively()
    }
}
