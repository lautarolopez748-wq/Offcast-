package com.offcast.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class OffcastApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Core.init(this)
        Notif.createChannels(this)
        Core.restoreAvailable = Core.library.isEmpty() && Backup.has(this)
        // Cada ~6 horas busca episodios nuevos...
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "follow-check",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<FollowWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        )
    }
}

object Notif {
    const val CH_DOWNLOADS = "downloads"
    const val CH_NEW = "new_episodes"
    const val EXTRA_FEED = "feed_url"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_DOWNLOADS, "Descargas", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_NEW, "Episodios nuevos y avisos", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    /** Abre la app; si viene [feedUrl], abre directo ese podcast. */
    fun openApp(ctx: Context, feedUrl: String? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (feedUrl != null) putExtra(EXTRA_FEED, feedUrl)
        }
        return PendingIntent.getActivity(
            ctx, feedUrl?.hashCode() ?: 0, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}

/**
 * Cola de descargas en un servicio en primer plano: sigue aunque cierres la app o bloquees la pantalla.
 * Baja de a un episodio por vez y muestra el avance en la notificación.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var worker: Job? = null
    private var running = false
    private var finished = 0
    private var lastNotify = 0L
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            Core.clearQueue()
            val w = worker
            if (w != null && w.isActive) w.cancel() else stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification("Preparando descarga…", "", 0f),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        launchWorker()
        return START_NOT_STICKY
    }

    private fun launchWorker() {
        synchronized(Core.lock) {
            if (running) return
            running = true
        }
        finished = 0
        worker = scope.launch {
            try {
                runQueue()
            } finally {
                val again: Boolean
                synchronized(Core.lock) {
                    running = false
                    again = Core.hasQueued()
                }
                if (again) launchWorker() else handler.post { stopIfIdle() }
            }
        }
    }

    private fun stopIfIdle() {
        synchronized(Core.lock) { if (running) return }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun runQueue() {
        if (!Core.canDownloadOnCurrentNetwork()) {
            Core.error = "Descargas pausadas: conectate a Wi-Fi o activá 'Usar datos móviles' en Ajustes."
            return
        }
        acquireWake()
        try {
            while (true) {
                val job = Core.nextJob() ?: break
                try {
                    download(job)
                    finished++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Core.error = "Falló la descarga de \"${job.title}\": ${e.message ?: "sin conexión"}"
                    Core.lastErrorJob = job
                } finally {
                    Core.progress.remove(job.id)
                }
            }
        } finally {
            releaseWake()
            if (finished > 0 && !Core.appVisible) postDone(finished)
        }
    }

    private suspend fun download(job: DownloadJob) {
        val part = File(Core.podDir, "${job.id}.part")
        val dest = File(Core.podDir, "${job.id}.mp3")
        try {
            val c = Net.open(job.url)
            try {
                val total = c.contentLengthLong
                c.inputStream.use { ins ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = 0f
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val f = done.toFloat() / total
                                if (f - last > 0.01f) {
                                    last = f
                                    Core.progress[job.id] = f
                                    updateNotification(job, f)
                                }
                            }
                        }
                    }
                }
            } finally {
                c.disconnect()
            }
            if (!part.renameTo(dest)) throw IOException("No se pudo guardar el archivo")
            Core.addSaved(Saved(job.id, job.title, job.podcast, job.artwork, dest.absolutePath))
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
    }

    private fun updateNotification(job: DownloadJob, f: Float) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 800) return
        lastNotify = now
        val remaining = Core.queuedCount()
        val title = if (remaining > 0) "Descargando (${finished + 1} de ${finished + 1 + remaining})" else "Descargando"
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(title, job.title, f))
    }

    private fun buildNotification(title: String, text: String, f: Float): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, Notif.CH_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, (f * 100).toInt(), f <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(Notif.openApp(this))
            .addAction(0, "Cancelar", cancel)
            .build()
    }

    private fun postDone(n: Int) {
        val text = if (n == 1) "1 episodio listo para escuchar sin internet" else "$n episodios listos para escuchar sin internet"
        val notif = NotificationCompat.Builder(this, Notif.CH_NEW)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Descarga terminada")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(Notif.openApp(this))
            .build()
        getSystemService(NotificationManager::class.java).notify(DONE_ID, notif)
    }

    private fun acquireWake() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "offcast:download").apply {
            acquire(3 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWake() {
        wake?.let { if (it.isHeld) it.release() }
        wake = null
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        releaseWake()
        // Lo que no llegó a bajarse deja de figurar "en progreso".
        Core.clearQueue()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "com.offcast.app.CANCEL_DOWNLOADS"
        private const val NOTIF_ID = 101
        private const val DONE_ID = 102

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        }
    }
}

/** Busca episodios nuevos de los podcasts seguidos y avisa. */
object FollowChecker {
    /** Id fijo: un solo aviso para todos los podcasts, en lugar de uno por feed. */
    private const val NEW_ID = 103

    suspend fun checkAll(notify: Boolean, autoDownload: Boolean = false) = withContext(Dispatchers.IO) {
        val results = Core.follows.map { f ->
            async {
                try {
                    val data = Feed.load(f.feedUrl)
                    val ids = Core.registerFeed(f.feedUrl, data.episodes.map { it.id })
                    if (ids.isEmpty()) return@async null
                    val newEps = data.episodes.filter { it.id in ids }
                    val jobs = if (autoDownload) newEps.map { e ->
                        DownloadJob(e.id, e.audioUrl, e.title, f.name, f.artwork)
                    } else emptyList()
                    Triple(f, newEps, jobs)
                } catch (e: Exception) {
                    // sin conexión o feed caído: se reintenta en la próxima vuelta
                    null
                }
            }
        }.awaitAll().filterNotNull()

        val fresh = results.map { it.first to it.second }
        val jobs = results.flatMap { it.third }
        if (autoDownload && jobs.isNotEmpty() && Core.canDownloadOnCurrentNetwork()) {
            Core.enqueue(jobs)
        }
        if (notify && fresh.isNotEmpty()) showNew(fresh)
    }

    private fun showNew(fresh: List<Pair<Follow, List<Episode>>>) {
        val ctx = Core.app
        val total = fresh.sumOf { it.second.size }
        val names = fresh.map { it.first.name }.distinct()
        val title = when {
            names.size == 1 && total == 1 -> "Nuevo en ${names[0]}"
            names.size == 1 -> "$total episodios nuevos en ${names[0]}"
            total == 1 -> "1 episodio nuevo en ${names.size} podcasts"
            else -> "$total episodios nuevos en ${names.size} podcasts"
        }
        // Hasta 3 títulos por podcast; si no entran todos, se resume al final.
        val lines = fresh.flatMap { (f, eps) ->
            eps.take(3).map { e -> if (names.size == 1) e.title else "${f.name}: ${e.title}" }
        }
        val more = total - lines.size
        val tail = if (more > 0) listOf("y $more más") else emptyList()
        val body = (lines + tail).joinToString("\n")
        val n = NotificationCompat.Builder(ctx, Notif.CH_NEW)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(Notif.openApp(ctx, fresh.singleOrNull()?.first?.feedUrl))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(NEW_ID, n)
    }
}

class FollowWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val auto = applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("auto_download", false)
        FollowChecker.checkAll(notify = true, autoDownload = auto)
        return Result.success()
    }
}
