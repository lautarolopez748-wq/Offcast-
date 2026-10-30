package com.offcast.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.Executors

/**
 * Estado compartido de la app. Lo usan la UI, el servicio de reproducción, el de descargas y el
 * worker de seguidos, así que nada de esto depende de que la pantalla esté abierta.
 */
object Core {
    lateinit var app: Application
        private set
    private lateinit var store: Store
    lateinit var podDir: File
        private set

    val lock = Any()
    private val io = Executors.newSingleThreadExecutor()
    private val queue = ArrayDeque<DownloadJob>()

    /** Cada cuánto, como mínimo, se reescribe `listen.json`. */
    private const val LISTEN_SAVE_MS = 15_000L
    private var lastListenSave = 0L
    private var listenDirty = false

    var library by mutableStateOf<List<Saved>>(emptyList())
        private set
    val listen = mutableStateMapOf<String, Listen>()
    val progress = mutableStateMapOf<String, Float>()
    var follows by mutableStateOf<List<Follow>>(emptyList())
        private set

    /** Último error de descarga en segundo plano (la UI lo muestra). */
    var error by mutableStateOf<String?>(null)
    var restoreAvailable by mutableStateOf(false)

    /** Job que falló, para poder reintentarlo desde la UI. */
    var lastErrorJob by mutableStateOf<DownloadJob?>(null)

    /** Descarta el error visible. */
    fun clearError() {
        error = null
        lastErrorJob = null
    }

    /** Vuelve a encolar la última descarga que falló. */
    fun retryLastError() {
        val job = lastErrorJob ?: return
        clearError()
        enqueue(listOf(job))
    }

    /** ¿Hay red permitida para descargar (Wi-Fi o datos autorizados)? */
    fun canDownloadOnCurrentNetwork(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        val onWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val allowMobile = app.getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("download_on_mobile", false)
        return onWifi || allowMobile
    }

    /** true mientras la pantalla de la app está visible. */
    @Volatile
    var appVisible = false

    var speed: Float = 1f
        set(value) {
            field = value
            app.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putFloat("speed", value).apply()
        }

    fun init(application: Application) {
        app = application
        store = Store(application.filesDir)
        podDir = File(application.getExternalFilesDir(null) ?: application.filesDir, "podcasts").apply { mkdirs() }
        library = store.load()
        listen.putAll(store.loadListen())
        follows = store.loadFollows()
        speed = application.getSharedPreferences("prefs", Context.MODE_PRIVATE).getFloat("speed", 1f)
    }

    /** Recarga biblioteca/progreso/seguidos desde disco (tras restaurar un respaldo). */
    fun reload() {
        library = store.load()
        listen.clear()
        listen.putAll(store.loadListen())
        follows = store.loadFollows()
    }

    fun addSaved(s: Saved) = synchronized(lock) {
        library = library.filter { it.id != s.id } + s
        val snap = library
        io.execute { store.save(snap) }
        OffcastWidgetProvider.updateAll(app)
    }

    fun removeSaved(s: Saved) = synchronized(lock) {
        library = library.filter { it.id != s.id }
        val snap = library
        io.execute { store.save(snap) }
        OffcastWidgetProvider.updateAll(app)
    }

    // ---------- Retomar / escuchados ----------

    /**
     * Actualiza el progreso en memoria siempre; lo escribe a disco como mucho cada [LISTEN_SAVE_MS],
     * salvo que [force] sea true. El reproductor llama esto cada 5 s: sin el límite se reescribía
     * `listen.json` completo todo el tiempo.
     */
    fun saveListen(id: String, pos: Long, dur: Long, force: Boolean = false) {
        val old = listen[id]
        val d = if (dur > 0) dur else (old?.dur ?: 0L)
        // Con menos de 30 s para el final lo damos por escuchado.
        val played = old?.played == true || (d > 0 && pos >= d - 30_000)
        val next = Listen(pos, d, played)
        if (next == old) return
        listen[id] = next
        if (!force && System.currentTimeMillis() - lastListenSave < LISTEN_SAVE_MS) {
            listenDirty = true
            return
        }
        persistListen()
    }

    /** Guarda ya lo pendiente (pausa, cambio de episodio, cierre de la app). */
    fun flushListen() {
        if (listenDirty) persistListen()
    }

    fun markPlayed(id: String, dur: Long) {
        val d = if (dur > 0) dur else (listen[id]?.dur ?: 0L)
        listen[id] = Listen(0L, d, true)
        persistListen()
    }

    /** Dónde arrancar un episodio: donde lo dejaste, o desde el principio si ya estaba por terminar. */
    fun startPosition(id: String): Long {
        val l = listen[id] ?: return 0L
        return if (l.dur > 0 && l.pos >= l.dur - 10_000) 0L else l.pos
    }

    private fun persistListen() {
        lastListenSave = System.currentTimeMillis()
        listenDirty = false
        val snap = HashMap<String, Listen>(listen)
        io.execute { store.saveListen(snap) }
    }

    // ---------- Cola de descargas ----------

    /** Agrega a la cola (sin repetir lo ya bajado o en curso) y arranca el servicio. Devuelve cuántos entraron. */
    fun enqueue(jobs: List<DownloadJob>): Int {
        var added = 0
        synchronized(lock) {
            for (j in jobs) {
                if (library.any { it.id == j.id } || progress.containsKey(j.id)) continue
                progress[j.id] = 0f
                queue.addLast(j)
                added++
            }
        }
        if (added > 0) {
            error = null
            lastErrorJob = null
            DownloadService.start(app)
        }
        return added
    }

    fun nextJob(): DownloadJob? = synchronized(lock) { queue.removeFirstOrNull() }

    fun hasQueued(): Boolean = synchronized(lock) { queue.isNotEmpty() }

    fun queuedCount(): Int = synchronized(lock) { queue.size }

    /** Snapshot de los jobs en cola (para la UI). */
    fun queuedJobs(): List<DownloadJob> = synchronized(lock) { queue.toList() }

    /** Quita un job de la cola (solo si aún no empezó a bajarse). */
    fun removeJob(id: String) = synchronized(lock) {
        progress.remove(id)
        queue.removeIf { it.id == id }
    }

    fun clearQueue() = synchronized(lock) {
        queue.forEach { progress.remove(it.id) }
        queue.clear()
    }

    // ---------- Podcasts seguidos ----------

    fun isFollowing(feedUrl: String) = follows.any { it.feedUrl == feedUrl }

    fun follow(p: Podcast, episodes: List<Episode>) = synchronized(lock) {
        if (isFollowing(p.feedUrl)) return@synchronized
        follows = follows + Follow(p.feedUrl, p.name, p.author, p.artwork, episodes.map { it.id }.toSet(), emptySet())
        persistFollows()
    }

    fun unfollow(feedUrl: String) = synchronized(lock) {
        follows = follows.filter { it.feedUrl != feedUrl }
        persistFollows()
    }

    /**
     * Registra los episodios que trae el feed. Devuelve los ids que no conocíamos (los nuevos) y los suma
     * a los "nuevos sin ver" del podcast. Si el podcast no está seguido, no hace nada.
     */
    fun registerFeed(feedUrl: String, ids: List<String>): Set<String> = synchronized(lock) {
        val f = follows.firstOrNull { it.feedUrl == feedUrl } ?: return@synchronized emptySet()
        val fresh = ids.filter { it !in f.known }.toSet()
        if (fresh.isNotEmpty()) {
            follows = follows.map {
                if (it.feedUrl == feedUrl) it.copy(known = it.known + ids, newIds = it.newIds + fresh) else it
            }
            persistFollows()
        }
        fresh
    }

    /** Al abrir un podcast: devuelve sus episodios nuevos y los marca como vistos. */
    fun consumeNew(feedUrl: String): Set<String> = synchronized(lock) {
        val f = follows.firstOrNull { it.feedUrl == feedUrl } ?: return@synchronized emptySet()
        val n = f.newIds
        if (n.isNotEmpty()) {
            follows = follows.map { if (it.feedUrl == feedUrl) it.copy(newIds = emptySet()) else it }
            persistFollows()
        }
        n
    }

    private fun persistFollows() {
        val snap = follows
        io.execute { store.saveFollows(snap) }
    }
}
