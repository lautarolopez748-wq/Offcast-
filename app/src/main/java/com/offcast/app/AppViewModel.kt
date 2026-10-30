package com.offcast.app

import android.app.Application
import android.content.ComponentName
import android.content.Context
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class Screen { Home, Library, Episodes, Player, Queue, Resume, Settings }

/** Una opción del menú "Descargar varios". */
data class BulkOption(val label: String, val detail: String, val episodes: List<Episode>)

/** Episodio sugerido: el episodio del feed más el podcast que lo trae. */
data class SuggestedEpisode(val episode: Episode, val follow: Follow)

private val DISCOVER_STOPWORDS = setOf(
    "the", "and", "podcast", "show", "radio", "con", "los", "las", "del", "de", "la", "el", "un", "una", "por", "para", "y", "en", "of", "to", "in", "a", "an"
)

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
    var newEpisodes by mutableStateOf<List<SuggestedEpisode>>(emptyList())
    var moreEpisodes by mutableStateOf<List<SuggestedEpisode>>(emptyList())
    var discoverPodcasts by mutableStateOf<List<Podcast>>(emptyList())
    var homeFilter by mutableStateOf("todo")
    var sunMode by mutableStateOf(false)
    var bulkOpen by mutableStateOf(false)

    // IA (resumen de episodios)
    var aiApiKey by mutableStateOf("")
    var aiEndpoint by mutableStateOf("")
    var aiModel by mutableStateOf("")

    // Filtro de episodios: "todos" | "nuevos" | "sin escuchar" | "descargados"
    var epFilter by mutableStateOf<String>("todos")
    var autoDownload by mutableStateOf(false)
    var downloadOnMobile by mutableStateOf(false)

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
    /** Oculta el mini reproductor sin cortar el audio. */
    var miniHidden by mutableStateOf(false)
    /** Temporizador de sueño en minutos (0 = desactivado). */
    var sleepTimerMinutes by mutableStateOf(0)
    private var sleepJob: Job? = null
    var summaryLoading by mutableStateOf(false)
    var summaryText by mutableStateOf<String?>(null)
    var summaryDialog by mutableStateOf(false)

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepTimerMinutes = minutes
        val until = if (minutes <= 0) 0L else System.currentTimeMillis() + minutes * 60 * 1000L
        app.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putLong("sleep_until", until).apply()
        if (minutes <= 0) return
        sleepJob = viewModelScope.launch {
            delay(minutes * 60 * 1000L)
            withController { it.pause() }
            sleepTimerMinutes = 0
            app.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putLong("sleep_until", 0L).apply()
        }
    }

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
        loadPrefs()
        viewModelScope.launch {
            FollowChecker.checkAll(notify = false, autoDownload = autoDownload)
            loadNewEpisodes()
            loadMoreOfWhatYouLike()
            loadDiscover()
        }
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
                    // ¿Es directamente un feed RSS?
                    try {
                        withContext(Dispatchers.IO) { Feed.load(url) }
                        loadPodcast(Podcast("", "", "", url))
                    } catch (e: Exception) {
                        // No es feed directo: probá a hacer scraping de la página para hallar el RSS
                        val feedUrl = withContext(Dispatchers.IO) { Finder.findFeedUrl(url) }
                        if (feedUrl != null) {
                            loadPodcast(Podcast("", "", "", feedUrl))
                        } else {
                            // Último recurso: directorio de Apple
                            val query = withContext(Dispatchers.IO) { Finder.queryFrom(q) }
                            val found = withContext(Dispatchers.IO) { Finder.search(query) }
                            if (found.isEmpty()) {
                                message = "No encontré ese podcast con descarga abierta. Probá escribiendo su nombre."
                            }
                            results = found
                        }
                    }
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

    /** Episodios de los podcasts que más escuchás, que todavía no escuchaste. */
    fun loadMoreOfWhatYouLike() {
        viewModelScope.launch {
            val out = withContext(Dispatchers.IO) {
                // Podcasts más escuchados (por tiempo acumulado).
                val listenedByPodcast = Core.library
                    .mapNotNull { s ->
                        val l = Core.listen[s.id]
                        if (l != null) s.podcast to (l.pos) else null
                    }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { it.value.sum() }
                    .entries.sortedByDescending { it.value }
                    .map { it.key }
                    .take(3)

                val follows = listenedByPodcast.mapNotNull { name ->
                    Core.follows.firstOrNull { it.name == name }
                }
                follows.map { f ->
                    async {
                        val collected = ArrayList<SuggestedEpisode>()
                        try {
                            val data = Feed.load(f.feedUrl)
                            for (e in data.episodes) {
                                if (Core.listen[e.id]?.played != true && Core.library.none { it.id == e.id }) {
                                    collected.add(SuggestedEpisode(e, f))
                                    if (collected.size >= 3) break
                                }
                            }
                        } catch (e: Exception) { }
                        collected
                    }
                }.awaitAll().flatten()
            }
            moreEpisodes = out
        }
    }

    fun openFollow(f: Follow) = open(Podcast(f.name, f.author, f.artwork, f.feedUrl))

    /** Carga los episodios nuevos de los podcasts que seguís (para la sección de sugerencias). */
    fun loadNewEpisodes() {
        viewModelScope.launch {
            val out = withContext(Dispatchers.IO) {
                Core.follows.map { f ->
                    async {
                        try {
                            val data = Feed.load(f.feedUrl)
                            data.episodes.filter { it.id in f.newIds }.map { SuggestedEpisode(it, f) }
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
            }
            newEpisodes = out
        }
    }

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
            isPlaying = false
            position = 0L
            duration = 0L
        }
        File(s.path).delete()
        Core.removeSaved(s)
    }

    /** El episodio que suena queda marcado como escuchado. */
    fun markPlayed() {
        val s = playing ?: return
        Core.markPlayed(s.id, duration)
        position = 0L
    }

    /** Borra la descarga del episodio que está en el reproductor. */
    fun deletePlaying() {
        playing?.let { delete(it) }
    }

    fun hideMini() {
        miniHidden = true
    }

    private fun toast(text: String) = Toast.makeText(app, text, Toast.LENGTH_LONG).show()

    // ---------- Reproductor ----------

    fun play(s: Saved) {
        playing = s
        miniHidden = false
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

    /** Barra inferior: Inicio y Biblioteca. */
    fun goHome() {
        screen = Screen.Home
    }

    fun goLibrary() {
        screen = Screen.Library
    }

    fun goQueue() {
        screen = Screen.Queue
    }

    fun goResume() {
        screen = Screen.Resume
    }

    fun goSettings() {
        screen = Screen.Settings
    }

    fun loadPrefs() {
        val prefs = app.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        autoDownload = prefs.getBoolean("auto_download", false)
        downloadOnMobile = prefs.getBoolean("download_on_mobile", false)
        sunMode = prefs.getBoolean("sun_mode", false)
        aiApiKey = prefs.getString("ai_api_key", "") ?: ""
        aiEndpoint = prefs.getString("ai_endpoint", "") ?: ""
        aiModel = prefs.getString("ai_model", "") ?: ""
    }

    fun saveSunMode(value: Boolean) {
        sunMode = value
        app.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putBoolean("sun_mode", value).apply()
    }

    fun loadDiscover() {
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                val countries = deviceCountries()
                val followedUrls = Core.follows.map { it.feedUrl }.toSet()
                // Semillas: los podcasts que seguís y los que tenés descargados.
                val seeds = (Core.follows.map { it.name } + Core.library.map { it.podcast })
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .take(5)
                val terms = if (seeds.isEmpty()) listOf("podcast") else seeds

                val collected = ArrayList<Podcast>()
                val seen = HashSet<String>()
                fun add(p: Podcast) {
                    if (p.feedUrl in followedUrls) return
                    if (seen.add(p.feedUrl)) collected.add(p)
                }

                for (term in terms) {
                    if (collected.size >= 8) break
                    for (country in countries) {
                        if (collected.size >= 8) break
                        try { Finder.search(term, country).forEach { add(it) } } catch (e: Exception) { }
                    }
                }

                // Si todavía faltan, buscamos por palabras clave de los nombres (temas).
                if (collected.size < 8) {
                    val keywords = terms
                        .flatMap { it.split(Regex("\\s+")) }
                        .map { it.trim().lowercase(Locale.getDefault()) }
                        .filter { it.length > 3 && it !in DISCOVER_STOPWORDS }
                        .distinct()
                        .take(4)
                    for (kw in keywords) {
                        if (collected.size >= 8) break
                        for (country in countries) {
                            if (collected.size >= 8) break
                            try { Finder.search(kw, country).forEach { add(it) } } catch (e: Exception) { }
                        }
                    }
                }

                if (collected.isEmpty()) {
                    try {
                        Finder.search("podcast", countries.first()).filter { it.feedUrl !in followedUrls }.take(8)
                    } catch (e: Exception) {
                        emptyList()
                    }
                } else {
                    collected.take(8)
                }
            }
            discoverPodcasts = found
        }
    }

    private fun deviceCountries(): List<String> {
        val loc = Locale.getDefault()
        val lang = loc.language
        val country = loc.country
        // Español: buscamos en varias tiendas hispanohablantes para no quedar en inglés.
        if (lang == "es") {
            val list = mutableListOf<String>()
            if (country.isNotBlank() && country != "US" && country in listOf("ES", "MX", "CL", "AR")) {
                // Solo tiendas que suelen devolver resultados en español para términos genéricos.
                if (country in listOf("ES", "MX", "CL")) list.add(country)
            }
            for (c in listOf("ES", "MX", "CL")) {
                if (c !in list) list.add(c)
            }
            return list.take(3)
        }
        if (lang == "pt") {
            val list = mutableListOf<String>()
            if (country.isNotBlank() && country != "US") list.add(country)
            if ("BR" !in list) list.add("BR")
            return list
        }
        return listOf(if (country.isNotBlank()) country else "US")
    }

    fun saveAutoDownload(value: Boolean) {
        autoDownload = value
        app.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putBoolean("auto_download", value).apply()
    }

    fun saveDownloadOnMobile(value: Boolean) {
        downloadOnMobile = value
        app.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putBoolean("download_on_mobile", value).apply()
    }

    fun saveAiSettings(key: String, endpoint: String, model: String) {
        aiApiKey = key
        aiEndpoint = endpoint
        aiModel = model
        app.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
            .putString("ai_api_key", key)
            .putString("ai_endpoint", endpoint)
            .putString("ai_model", model)
            .apply()
    }

    fun summarizeCurrent() {
        val s = playing ?: return
        summaryLoading = true
        summaryText = null
        summaryDialog = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    if (aiApiKey.isBlank()) return@withContext "Configurá una API key en Ajustes."
                    val url = aiEndpoint.ifBlank { "https://api.openai.com/v1/chat/completions" }
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Authorization", "Bearer $aiApiKey")
                    conn.doOutput = true
                    val body = org.json.JSONObject().apply {
                        put("model", aiModel.ifBlank { "gpt-4o-mini" })
                        put("messages", org.json.JSONArray().apply {
                            put(org.json.JSONObject().put("role", "system").put("content", "Resumí en español en 5 viñetas breves."))
                            put(org.json.JSONObject().put("role", "user").put("content", "Podcast: ${s.podcast}\nEpisodio: ${s.title}"))
                        })
                        put("max_tokens", 250)
                    }.toString()
                    conn.outputStream.use { it.write(body.toByteArray()) }
                    val code = conn.responseCode
                    val text = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
                    conn.disconnect()
                    if (code !in 200..299) "Error $code: $text"
                    else {
                        val j = org.json.JSONObject(text)
                        j.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                    }
                } catch (e: Exception) {
                    "No se pudo: ${e.message ?: "error"}"
                }
            }
            summaryText = result
            summaryLoading = false
        }
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
