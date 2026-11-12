package app.flux.downloader.engine

import android.app.Application
import android.net.Uri
import app.flux.downloader.DownloadService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class Status { QUEUED, RUNNING, SAVING, DONE, FAILED, CANCELED }

val ACTIVE_STATUSES = setOf(Status.QUEUED, Status.RUNNING, Status.SAVING)

data class DownloadItem(
    val id: String,
    val title: String,
    val thumbnail: String?,
    val label: String,
    val status: Status = Status.QUEUED,
    val progress: Float = 0f,
    val etaSec: Long = 0,
    val uri: Uri? = null,
    val mime: String? = null,
    val error: String? = null,
)

/**
 * Estado global de descargas. Vive fuera de la UI: sigue funcionando si la app
 * pasa a segundo plano gracias a [DownloadService].
 */
object DownloadManager {

    private const val MAX_PARALLEL = 2

    private lateinit var app: Application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Semaphore(MAX_PARALLEL)
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

    fun init(application: Application) {
        app = application
        Notifications.createChannels(app)
    }

    fun active(): List<DownloadItem> = _downloads.value.filter { it.status in ACTIVE_STATUSES }

    fun start(info: MediaInfo, choice: Choice) {
        val id = UUID.randomUUID().toString()
        val label = when {
            choice.audioOnly -> choice.audioFormat.uppercase()
            choice.height != null -> "${choice.height}p"
            else -> "Mejor calidad"
        }
        _downloads.update { listOf(DownloadItem(id, info.title, info.thumbnail, label)) + it }
        DownloadService.start(app)

        jobs[id] = scope.launch {
            try {
                gate.withPermit {
                    update(id) { if (it.status == Status.CANCELED) it else it.copy(status = Status.RUNNING) }
                    val file = Engine.download(app, info.url, choice, id) { pct, eta ->
                        update(id) { it.copy(progress = (pct / 100f).coerceIn(0f, 1f), etaSec = eta) }
                    }
                    update(id) { it.copy(status = Status.SAVING, progress = 1f) }
                    val saved = Storage.saveToDownloads(app, file)
                        ?: error("No se pudo guardar el archivo en Descargas")
                    update(id) { it.copy(status = Status.DONE, uri = saved.first, mime = saved.second) }
                }
                Notifications.done(app, info.title)
            } catch (e: CancellationException) {
                update(id) { it.copy(status = Status.CANCELED) }
                throw e
            } catch (e: Exception) {
                update(id) {
                    if (it.status == Status.CANCELED) it
                    else it.copy(status = Status.FAILED, error = friendlyError(e))
                }
            } finally {
                Engine.cleanup(app, id)
                jobs.remove(id)
            }
        }
    }

    fun cancel(id: String) {
        update(id) { it.copy(status = Status.CANCELED) }
        Engine.cancel(id)
        jobs[id]?.cancel()
    }

    fun cancelAll() {
        active().forEach { cancel(it.id) }
    }

    fun remove(id: String) {
        _downloads.update { list -> list.filterNot { it.id == id } }
    }

    private fun update(id: String, change: (DownloadItem) -> DownloadItem) {
        _downloads.update { list -> list.map { if (it.id == id) change(it) else it } }
    }
}
