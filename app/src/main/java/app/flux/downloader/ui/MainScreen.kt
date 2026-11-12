package app.flux.downloader.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flux.downloader.engine.Choice
import app.flux.downloader.engine.DownloadItem
import app.flux.downloader.engine.Engine
import app.flux.downloader.engine.MediaInfo
import app.flux.downloader.engine.Status
import coil.compose.AsyncImage

@Composable
fun MainScreen(vm: MainViewModel) {
    val link by vm.link.collectAsStateWithLifecycle()
    val analysis by vm.analysis.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val engineReady by Engine.ready.collectAsStateWithLifecycle()
    val engineError by Engine.error.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Cabecera
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Flux", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(".", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = FluxOrange)
                }
                Text(
                    "Video y audio desde miles de sitios",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Entrada del enlace
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(14.dp)) {
                    OutlinedTextField(
                        value = link,
                        onValueChange = vm::onLinkChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Pega aquí el enlace del video") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { vm.analyze() }),
                        trailingIcon = {
                            IconButton(onClick = {
                                clipboard.getText()?.text?.let { vm.onSharedText(it) }
                            }) {
                                Icon(Icons.Default.ContentPaste, contentDescription = "Pegar y analizar")
                            }
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = vm::analyze,
                        enabled = link.isNotBlank() && analysis !is Analysis.Loading,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Analizar enlace") }
                }
            }
        }

        // Estado del motor
        if (engineError != null) {
            item { Text(engineError.orEmpty(), color = MaterialTheme.colorScheme.error) }
        } else if (!engineReady) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Preparando el motor de descargas…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Resultado del análisis
        when (val a = analysis) {
            Analysis.Idle -> Unit
            Analysis.Loading -> item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is Analysis.Error -> item { Text(a.message, color = MaterialTheme.colorScheme.error) }
            is Analysis.Ready -> item { PreviewCard(a.info, onDownload = vm::startDownload) }
        }

        // Lista de descargas
        if (downloads.isNotEmpty()) {
            item { Text("Descargas", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
            items(downloads, key = { it.id }) { d ->
                DownloadRow(
                    d = d,
                    onCancel = { vm.cancel(d.id) },
                    onRemove = { vm.remove(d.id) },
                    onOpen = { openFile(context, d) },
                )
            }
        }
    }
}

@Composable
private fun PreviewCard(info: MediaInfo, onDownload: (MediaInfo, Choice) -> Unit) {
    var audioOnly by remember(info.url) { mutableStateOf(false) }
    var height by remember(info.url) { mutableStateOf(info.heights.firstOrNull()) }
    var audioFormat by remember(info.url) { mutableStateOf("mp3") }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AsyncImage(
                model = info.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Text(info.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                info.uploader,
                info.durationSec.takeIf { it > 0 }?.let(::formatDuration),
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !audioOnly, onClick = { audioOnly = false }, label = { Text("Video") })
                FilterChip(selected = audioOnly, onClick = { audioOnly = true }, label = { Text("Solo audio") })
            }

            if (audioOnly) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("mp3", "m4a", "opus").forEach { f ->
                        FilterChip(selected = audioFormat == f, onClick = { audioFormat = f }, label = { Text(f.uppercase()) })
                    }
                }
            } else if (info.heights.isEmpty()) {
                Text("Se descargará en la mejor calidad disponible", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(info.heights) { h ->
                        FilterChip(selected = height == h, onClick = { height = h }, label = { Text("${h}p") })
                    }
                }
            }

            Button(
                onClick = { onDownload(info, Choice(audioOnly, if (audioOnly) null else height, audioFormat)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Descargar")
            }
        }
    }
}

@Composable
private fun DownloadRow(d: DownloadItem, onCancel: () -> Unit, onRemove: () -> Unit, onOpen: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = d.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(
                    statusText(d),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                if (d.status == Status.RUNNING || d.status == Status.SAVING) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { d.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
            when (d.status) {
                Status.RUNNING, Status.QUEUED -> IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "Cancelar")
                }
                Status.SAVING -> Unit
                Status.DONE -> {
                    IconButton(onClick = onOpen) { Icon(Icons.Default.PlayArrow, contentDescription = "Abrir") }
                    IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, contentDescription = "Quitar de la lista") }
                }
                else -> IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = "Quitar de la lista")
                }
            }
        }
    }
}

private fun statusText(d: DownloadItem): String = when (d.status) {
    Status.QUEUED -> "${d.label} · En cola"
    Status.RUNNING -> "${d.label} · ${(d.progress * 100).toInt()}%" +
        if (d.etaSec > 0) " · quedan ${formatDuration(d.etaSec)}" else ""
    Status.SAVING -> "${d.label} · Guardando en Descargas/Flux…"
    Status.DONE -> "${d.label} · Guardado en Descargas/Flux"
    Status.FAILED -> d.error ?: "Error en la descarga"
    Status.CANCELED -> "Cancelado"
}

private fun openFile(ctx: Context, d: DownloadItem) {
    val uri = d.uri ?: return
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, d.mime ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(intent) }
}
