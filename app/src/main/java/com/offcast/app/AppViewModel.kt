package com.offcast.app

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen { Home, Episodes, Player }

/** Una opción del menú "Descargar varios". */
data class BulkOption(val label: String, val detail: String, val episodes: List<Episode>)

class AppViewModel(private val app: Application) : AndroidViewModel(app) {
    var screen by mutableStateOf(Screen.Home)
    private var prev = Screen.Home
    var input by mutableStateOf("")
    var loading by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var results by mutableStateOf<List<Podcast>>(emptyList())
    var current by mutableStateOf<Podcast?>(null)
    var episodes by mutableStateOf<List<Episode>>(emptyList())
    var newIds by mutableStateOf<Set<String>>(emptySet())
    var bulkOpen by mutableStateOf(false)

    // El estado vive en Core: así lo comparten los servicios aunque la pantalla no esté.
    val library: List<Saved> get() = Core.library
    val progress get() = Core.progress
    val listen get() = Core.listen
    val follows: List<Follow> get() = Core.follows

    var playing by mutableStateOf<Saved?>(null)
    var isPlaying by mutableStateOf(false)
    var position by mutableStateOf(0L)
    var duration by mutableStateOf(0L)
    var speed by mutableStateOf(Core.speed)

    // ---------- Conexión con el servicio de reproducción ----------

    private var controller: MediaController? = null
    private val pendingActions = ArrayList<(MediaController) -> Unit>()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            this@AppViewModel.isPlaying = isPlaying
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                controller?.let { duration = it.duration.coerceAtLeast(0L) }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId
            playing = if (id.isNullOrEmpty()) null else library.firstOrNull { it.id == id }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            speed = playbackParameters.speed
        }
    }

    init {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            val c = try {
                future.get()
            } catch (e: Exception) {
                return@addListener
            }
            controller = c
            c.addListener(playerListener)
            // Si el servicio ya estaba reproduciendo (la app se reabrió), nos ponemos al día.
            val id = c.currentMediaItem?.mediaId
            if (!id.isNullOrEmpty()) {
                playing = library.firstOrNull { it.id == id }
                isPlaying = c.isPlaying
                position = c.currentPosition
                duration = c.duration.coerceAtLeast(0L)
                speed = c.playbackParameters.speed
            } else {
                c.setPlaybackSpeed(Core.speed)
                speed = Core.speed
            }
            pendingActions.forEach { it(c) }
            pendingActions.clear()
        }, ContextCompat.getMainExecutor(app))

        viewModelScope.launch {
            while (true) {
                controller?.let { if (it.isPlaying) position = it.currentPosition }
                delay(500)
            }
        }

        // Al abrir la app, revisa si hay episodios nuevos en lo que seguís.
        viewModelScope.launch { FollowChecker.checkAll(notify = false) }
    }

    private fun withController(action: (MediaController) -> Unit) {
        val c = controller
        if (c != null) action(c) else pendingActions.add(action)
    }

    // ---------- Buscar ----------

    fun submit(text: String = input) {
        val q = text.trim()
        if (q.isEmpty()) return
        input = q
        viewModelScope.launch {
            loading = true
            message = null
            results = emptyList()
            try {
                val url = Finder.firstUrl(q)
                if (url != null && !Finder.isStreamingLink(url)) {
                    // Link directo a un feed RSS
                    loadPodcast(Podcast("", "", "", url))
                } else {
                    val query = withContext(Dispatchers.IO) { Finder.queryFrom(q) }
                    val found = withContext(Dispatchers.IO) { Finder.search(query) }
                    if (found.isEmpty()) {
                        message = "No encontré ese podcast con descarga abierta. Probá escribiendo su nombre."
                    }
                    results = found
                }
            } catch (e: Exception) {
                message = "No se pudo: ${e.message ?: "error de conexión"}"
            } finally {
                loading = false
            }
        }
    }

    /** Botón de borrado rápido: vacía la barra y limpia lo que había salido de la búsqueda. */
    fun clearSearch() {
        input = ""
        results = emptyList()
        message = null
    }

    private suspend fun loadPodcast(p: Podcast) {
        val data = withContext(Dispatchers.IO) { Feed.load(p.feedUrl) }
        val pod = Podcast(
            if (p.name.isNotEmpty()) p.name else data.title,
            p.author,
            if (p.artwork.isNotEmpty()) p.artwork else data.image,
            p.feedUrl
        )
        current = pod
        episodes = data.episodes
        // Si lo seguís: lo que apareció desde la última vez queda marcado como NUEVO.
        Core.registerFeed(p.feedUrl, data.episodes.map { it.id })
        newIds = Core.consumeNew(p.feedUrl)
        screen = Screen.Episodes
    }

    fun open(p: Podcast) {
        viewModelScope.launch {
            loading = true
            message = null
            try {
                loadPodcast(p)
            } catch (e: Exception) {
                message = "No se pudo abrir: ${e.message ?: "error"}"
            } finally {
                loading = false
            }
        }
    }

    fun openFollow(f: Follow) = open(Podcast(f.name, f.author, f.artwork, f.feedUrl))

    // ---------- Seguir ----------

    fun isFollowing(): Boolean = current?.let { p -> follows.any { it.feedUrl == p.feedUrl } } ?: false

    fun toggleFollow() {
        val p = current ?: return
        if (isFollowing()) {
            Core.unfollow(p.feedUrl)
        } else {
            Core.follow(p, episodes)
            toast("Siguiendo ${p.name}. Te avisamos cuando haya episodios nuevos.")
        }
    }

    // ---------- Descargas ----------

    fun idFor(e: Episode): String = e.id

    private fun jobFor(e: Episode, pod: Podcast) = DownloadJob(e.id, e.audioUrl, e.title, pod.name, pod.artwork)

    fun download(e: Episode) {
        val pod = current ?: return
        message = null
        Core.enqueue(listOf(jobFor(e, pod)))
    }

    fun downloadMany(list: List<Episode>) {
        val pod = current ?: return
        message = null
        val n = Core.enqueue(list.map { jobFor(it, pod) })
        toast(
            if (n == 0) "Ya está todo descargado"
            else "Bajando $n ${if (n == 1) "episodio" else "episodios"}. Podés cerrar la app."
        )
    }

    /** Opciones de descarga múltiple, sin contar lo que ya está bajado o en cola. */
    fun bulkOptions(): List<BulkOption> {
        fun pending(l: List<Episode>) =
            l.filter { e -> !Core.progress.containsKey(e.id) && Core.library.none { it.id == e.id } }

        val out = ArrayList<BulkOption>()
        fun add(label: String, source: List<Episode>, extra: String = "") {
            val p = pending(source)
            if (p.isEmpty()) return
            if (out.any { o -> o.episodes.map { it.id } == p.map { it.id } }) return // misma lista que otra opción
            val base = if (p.size == 1) "1 episodio por bajar" else "${p.size} episodios por bajar"
            out.add(BulkOption(label, base + extra, p))
        }
        add("Los últimos 5", episodes.take(5))
        add("Los últimos 10", episodes.take(10))
        episodes.filter { it.season > 0 }
            .groupBy { it.season }
            .entries.sortedByDescending { it.key }
            .forEach { (n, l) -> add("Toda la temporada $n", l) }
        add("Todos los episodios", episodes, " · ocupa bastante espacio")
        return out
    }

    fun delete(s: Saved) {
        if (playing?.id == s.id) {
            controller?.run {
                stop()
                clearMediaItems()
            }
            playing = null
        }
        File(s.path).delete()
        Core.removeSaved(s)
    }

    private fun toast(text: String) = Toast.makeText(app, text, Toast.LENGTH_LONG).show()

    // ---------- Reproductor ----------

    fun play(s: Saved) {
        playing = s
        withController { c ->
            if (c.currentMediaItem?.mediaId != s.id) {
                position = Core.startPosition(s.id)
                duration = Core.listen[s.id]?.dur ?: 0L
                c.setMediaItem(mediaItem(s), Core.startPosition(s.id))
                c.prepare()
            }
            c.setPlaybackSpeed(Core.speed)
            c.play()
        }
        openPlayer()
    }

    private fun mediaItem(s: Saved): MediaItem {
        val meta = MediaMetadata.Builder().setTitle(s.title).setArtist(s.podcast)
        if (s.artwork.isNotEmpty()) meta.setArtworkUri(Uri.parse(s.artwork))
        return MediaItem.Builder()
            .setMediaId(s.id)
            .setUri(Uri.fromFile(File(s.path)))
            .setMediaMetadata(meta.build())
            .build()
    }

    fun openPlayer() {
        if (screen != Screen.Player) prev = screen
        screen = Screen.Player
    }

    fun back() {
        screen = if (screen == Screen.Player) prev else Screen.Home
    }

    fun toggle() {
        withController { c -> if (c.isPlaying) c.pause() else c.play() }
    }

    fun seekTo(ms: Long) {
        withController { it.seekTo(ms) }
        position = ms
    }

    fun skip(ms: Long) {
        withController { c -> if (ms < 0) c.seekBack() else c.seekForward() }
        val max = if (duration > 0) duration else Long.MAX_VALUE
        position = (position + ms).coerceIn(0L, max)
    }

    fun changeSpeed(value: Float) {
        speed = value
        Core.speed = value
        withController { it.setPlaybackSpeed(value) }
    }

    override fun onCleared() {
        // El reproductor NO se libera: sigue vivo en el servicio. Solo soltamos la conexión.
        controller?.let {
            it.removeListener(playerListener)
            it.release()
        }
        controller = null
    }
}
