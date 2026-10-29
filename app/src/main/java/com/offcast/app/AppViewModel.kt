package com.offcast.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

enum class Screen { Home, Episodes, Player }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app.filesDir)
    private val podDir = File(app.getExternalFilesDir(null) ?: app.filesDir, "podcasts").apply { mkdirs() }

    var screen by mutableStateOf(Screen.Home)
    private var prev = Screen.Home
    var input by mutableStateOf("")
    var loading by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var results by mutableStateOf<List<Podcast>>(emptyList())
    var current by mutableStateOf<Podcast?>(null)
    var episodes by mutableStateOf<List<Episode>>(emptyList())
    var library by mutableStateOf(store.load())
    val progress = mutableStateMapOf<String, Float>()

    var playing by mutableStateOf<Saved?>(null)
    var isPlaying by mutableStateOf(false)
    var position by mutableStateOf(0L)
    var duration by mutableStateOf(0L)

    val player: ExoPlayer = ExoPlayer.Builder(app).build().also { exo ->
        exo.setWakeMode(C.WAKE_MODE_LOCAL)
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(on: Boolean) {
                isPlaying = on
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) duration = exo.duration.coerceAtLeast(0L)
            }
        })
    }

    init {
        viewModelScope.launch {
            while (true) {
                if (player.isPlaying) position = player.currentPosition
                delay(500)
            }
        }
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

    private suspend fun loadPodcast(p: Podcast) {
        val data = withContext(Dispatchers.IO) { Feed.load(p.feedUrl) }
        current = Podcast(
            if (p.name.isNotEmpty()) p.name else data.title,
            p.author,
            if (p.artwork.isNotEmpty()) p.artwork else data.image,
            p.feedUrl
        )
        episodes = data.episodes
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

    // ---------- Descargas ----------

    fun idFor(e: Episode): String =
        MessageDigest.getInstance("MD5").digest(e.audioUrl.toByteArray()).joinToString("") { "%02x".format(it) }

    fun download(e: Episode) {
        val id = idFor(e)
        if (progress.containsKey(id) || library.any { it.id == id }) return
        val pod = current ?: return
        progress[id] = 0f
        message = null
        viewModelScope.launch {
            val file = File(podDir, "$id.mp3")
            try {
                withContext(Dispatchers.IO) {
                    val c = Net.open(e.audioUrl)
                    try {
                        val total = c.contentLengthLong
                        c.inputStream.use { ins ->
                            file.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                var done = 0L
                                var last = 0f
                                while (true) {
                                    val n = ins.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    if (total > 0) {
                                        val f = done.toFloat() / total
                                        if (f - last > 0.01f) {
                                            last = f
                                            progress[id] = f
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        c.disconnect()
                    }
                }
                library = library + Saved(id, e.title, pod.name, pod.artwork, file.absolutePath)
                store.save(library)
            } catch (ex: Exception) {
                message = "Falló la descarga: ${ex.message ?: "sin conexión"}"
                file.delete()
            } finally {
                progress.remove(id)
            }
        }
    }

    fun delete(s: Saved) {
        if (playing?.id == s.id) {
            player.stop()
            playing = null
        }
        File(s.path).delete()
        library = library.filter { it.id != s.id }
        store.save(library)
    }

    // ---------- Reproductor ----------

    fun play(s: Saved) {
        if (playing?.id != s.id) {
            playing = s
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(s.path))))
            player.prepare()
            position = 0L
            duration = 0L
        }
        player.play()
        openPlayer()
    }

    fun openPlayer() {
        if (screen != Screen.Player) prev = screen
        screen = Screen.Player
    }

    fun back() {
        screen = if (screen == Screen.Player) prev else Screen.Home
    }

    fun toggle() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekTo(ms: Long) {
        player.seekTo(ms)
        position = ms
    }

    fun skip(ms: Long) {
        val max = if (duration > 0) duration else Long.MAX_VALUE
        seekTo((player.currentPosition + ms).coerceIn(0L, max))
    }

    override fun onCleared() {
        player.release()
    }
}
