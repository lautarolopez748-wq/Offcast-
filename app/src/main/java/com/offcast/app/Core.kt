package com.offcast.app

import android.app.Application
import android.content.Context
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

    var library by mutableStateOf<List<Saved>>(emptyList())
        private set
    val listen = mutableStateMapOf<String, Listen>()
    val progress = mutableStateMapOf<String, Float>()
    var follows by mutableStateOf<List<Follow>>(emptyList())
        private set

    /** Último error de descarga en segundo plano (la UI lo muestra). */
    var error by mutableStateOf<String?>(null)

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

    // ---------- Biblioteca ----------

    fun addSaved(s: Saved) = synchronized(lock) {
        library = library.filter { it.id != s.id } + s
        val snap = library
        io.execute { store.save(snap) }
    }

    fun removeSaved(s: Saved) = synchronized(lock) {
        library = library.filter { it.id != s.id }
        val snap = library
        io.execute { store.save(snap) }
    }

    // ---------- Retomar / escuchados ----------

    fun saveListen(id: String, pos: Long, dur: Long) {
        val old = listen[id]
        val d = if (dur > 0) dur else (old?.dur ?: 0L)
        // Con menos de 30 s para el final lo damos por escuchado.
        val played = old?.played == true || (d > 0 && pos >= d - 30_000)
        listen[id] = Listen(pos, d, played)
        persistListen()
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
            DownloadService.start(app)
        }
        return added
    }

    fun nextJob(): DownloadJob? = synchronized(lock) { queue.removeFirstOrNull() }

    fun hasQueued(): Boolean = synchronized(lock) { queue.isNotEmpty() }

    fun queuedCount(): Int = synchronized(lock) { queue.size }

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
