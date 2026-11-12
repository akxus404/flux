package app.flux.downloader.engine

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.flux.downloader.DownloadService
import app.flux.downloader.MainActivity

object Notifications {
    const val CHANNEL_PROGRESS = "downloads_progress"
    const val CHANNEL_DONE = "downloads_done"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Descargas en curso", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Descargas completadas", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Notificación persistente del servicio en primer plano. */
    fun progress(ctx: Context, active: List<DownloadItem>): Notification {
        val running = active.filter { it.status == Status.RUNNING || it.status == Status.SAVING }
        val queued = active.count { it.status == Status.QUEUED }
        val avg = if (running.isEmpty()) 0f else running.map { it.progress }.average().toFloat()
        val text = buildString {
            append(if (running.size == 1) "1 descargando" else "${running.size} descargando")
            if (queued > 0) append(" · $queued en cola")
        }
        val cancelAll = PendingIntent.getService(
            ctx, 1,
            Intent(ctx, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(ctx, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Flux")
            .setContentText(text)
            .setProgress(100, (avg * 100).toInt(), running.isEmpty())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .addAction(0, "Cancelar todo", cancelAll)
            .build()
    }

    fun done(ctx: Context, title: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(ctx, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Descarga completada")
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        NotificationManagerCompat.from(ctx).notify(title.hashCode(), n)
    }
}
