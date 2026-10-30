package com.offcast.app

import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale

data class Podcast(val name: String, val author: String, val artwork: String, val feedUrl: String)

fun md5(s: String): String =
    MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

data class Episode(
    val title: String,
    val audioUrl: String,
    val date: String,
    val duration: String,
    val season: Int = 0,
    val ts: Long = 0L
) {
    /** Identificador estable: sirve igual para el episodio en el feed y para el archivo descargado. */
    val id: String = md5(audioUrl)
}

data class Saved(val id: String, val title: String, val podcast: String, val artwork: String, val path: String)

data class FeedData(val title: String, val image: String, val episodes: List<Episode>)

/** Por dónde ibas en un episodio. */
data class Listen(val pos: Long, val dur: Long, val played: Boolean)

/** Podcast seguido. [known] = episodios ya vistos; [newIds] = episodios nuevos que todavía no abriste. */
data class Follow(
    val feedUrl: String,
    val name: String,
    val author: String,
    val artwork: String,
    val known: Set<String>,
    val newIds: Set<String>
)

data class DownloadJob(val id: String, val url: String, val title: String, val podcast: String, val artwork: String)

object Net {
    fun open(url: String): HttpURLConnection {
        var current = url
        repeat(8) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "Offcast/0.1")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw Exception("Redirección inválida")
                current = URL(URL(current), loc).toString()
                c.disconnect()
            } else if (code in 200..299) {
                return c
            } else {
                c.disconnect()
                throw Exception("Error $code")
            }
        }
        throw Exception("Demasiadas redirecciones")
    }

    fun text(url: String): String {
        val c = open(url)
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

object Finder {
    fun firstUrl(text: String): String? = Regex("https?://\\S+").find(text)?.value

    fun isStreamingLink(url: String): Boolean =
        "spotify.com" in url || "spotify.link" in url || "youtube.com" in url || "youtu.be" in url

    /** De un link de Spotify/YouTube (o un nombre escrito) saca el texto para buscar el podcast. */
    fun queryFrom(input: String): String {
        val url = firstUrl(input) ?: return input.trim()
        var real = url
        if ("spotify.link" in url) {
            val c = Net.open(url)
            real = c.url.toString()
            c.disconnect()
        }
        return if ("spotify" in real) {
            JSONObject(Net.text("https://open.spotify.com/oembed?url=" + Net.enc(real))).getString("title")
        } else {
            val j = JSONObject(Net.text("https://www.youtube.com/oembed?format=json&url=" + Net.enc(real)))
            j.optString("author_name", j.optString("title"))
        }
    }

    /** De una página web, busca la URL del feed RSS (<link rel="alternate" type="application/rss+xml">). */
    fun findFeedUrl(pageUrl: String): String? = try {
        val html = Net.text(pageUrl)
        val regex = Regex("""<link[^>]+type\s*=\s*["']application/rss\+xml["'][^>]+href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val match = regex.find(html) ?: return null
        val href = match.groupValues[1]
        if (href.startsWith("http")) href else {
            // URL relativa: resolver contra la base
            val base = URL(pageUrl)
            URL(base, href).toString()
        }
    } catch (e: Exception) {
        null
    }

    /** Busca el podcast en el directorio público de Apple y devuelve los feeds RSS. */
    fun search(query: String, country: String = "US"): List<Podcast> {
        val json = JSONObject(
            Net.text("https://itunes.apple.com/search?media=podcast&entity=podcast&limit=8&country=$country&term=" + Net.enc(query))
        )
        val arr = json.getJSONArray("results")
        val out = ArrayList<Podcast>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val feed = o.optString("feedUrl")
            if (feed.isEmpty()) continue
            out.add(Podcast(o.optString("collectionName"), o.optString("artistName"), o.optString("artworkUrl600"), feed))
        }
        return out
    }
}

object Feed {
    private data class Cached(val data: FeedData, val at: Long)
    private val cache = HashMap<String, Cached>()
    private const val TTL_MS = 30 * 60 * 1000L
    private val dateFormats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "dd MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm:ss Z"
    )

    fun load(feedUrl: String): FeedData {
        synchronized(cache) {
            val c = cache[feedUrl]
            if (c != null && System.currentTimeMillis() - c.at < TTL_MS) return c.data
        }
        val data = loadUncached(feedUrl)
        synchronized(cache) { cache[feedUrl] = Cached(data, System.currentTimeMillis()) }
        return data
    }

    private fun loadUncached(feedUrl: String): FeedData {
        val c = Net.open(feedUrl)
        try {
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(c.inputStream, null)
            var channelTitle = ""
            var channelImage = ""
            val eps = ArrayList<Episode>()
            var inItem = false
            var title = ""
            var url = ""
            var date = ""
            var dur = ""
            var season = 0
            var ts = 0L
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "item" -> {
                            inItem = true
                            title = ""
                            url = ""
                            date = ""
                            dur = ""
                            season = 0
                            ts = 0L
                        }
                        "title" -> {
                            val t = p.nextText().trim()
                            if (inItem) title = t else if (channelTitle.isEmpty()) channelTitle = t
                        }
                        "enclosure" -> if (inItem) url = p.getAttributeValue(null, "url") ?: ""
                        "pubDate" -> if (inItem) {
                            val raw = p.nextText()
                            date = shortDate(raw)
                            ts = parseDate(raw)
                        }
                        "itunes:duration" -> if (inItem) dur = p.nextText().trim()
                        "itunes:season" -> if (inItem) season = p.nextText().trim().toIntOrNull() ?: 0
                        "itunes:image" -> if (!inItem && channelImage.isEmpty()) {
                            channelImage = p.getAttributeValue(null, "href") ?: ""
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG && p.name == "item") {
                    inItem = false
                    if (url.isNotEmpty()) {
                        eps.add(Episode(title.ifEmpty { "Sin título" }, url, date, formatDuration(dur), season, ts))
                    }
                }
                ev = p.next()
            }
            // Más nuevos primero (si el feed trae fechas); si no, se respeta el orden del feed.
            val ordered = if (eps.isNotEmpty() && eps.all { it.ts > 0 }) eps.sortedByDescending { it.ts } else eps
            return FeedData(channelTitle, channelImage, ordered.distinctBy { it.id }.take(150))
        } finally {
            c.disconnect()
        }
    }

    private fun parseDate(s: String): Long {
        val t = s.trim()
        for (f in dateFormats) {
            try {
                return SimpleDateFormat(f, Locale.US).parse(t)?.time ?: continue
            } catch (e: Exception) {
                // probar el siguiente formato
            }
        }
        return 0L
    }

    private fun shortDate(s: String): String {
        val t = s.trim().substringAfter(", ")
        return t.split(" ").take(3).joinToString(" ")
    }

    private fun formatDuration(d: String): String {
        if (d.isEmpty()) return ""
        if (d.contains(":")) return d
        val sec = d.toIntOrNull() ?: return ""
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return if (h > 0) "$h h $m min" else "$m min"
    }
}

/** Guarda biblioteca, progreso de escucha y podcasts seguidos en archivos JSON. */
class Store(private val dir: File) {
    private val libFile = File(dir, "library.json")
    private val listenFile = File(dir, "listen.json")
    private val followFile = File(dir, "follows.json")

    private fun write(file: File, text: String) {
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    // ----- Biblioteca -----

    fun load(): List<Saved> {
        if (!libFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(libFile.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Saved(o.getString("id"), o.getString("title"), o.getString("podcast"), o.getString("artwork"), o.getString("path"))
            }.filter { File(it.path).exists() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(list: List<Saved>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("podcast", it.podcast)
                    .put("artwork", it.artwork)
                    .put("path", it.path)
            )
        }
        write(libFile, arr.toString())
    }

    // ----- Progreso de escucha -----

    fun loadListen(): Map<String, Listen> {
        if (!listenFile.exists()) return emptyMap()
        return try {
            val o = JSONObject(listenFile.readText())
            val out = HashMap<String, Listen>()
            for (k in o.keys()) {
                val e = o.getJSONObject(k)
                out[k] = Listen(e.optLong("p"), e.optLong("d"), e.optBoolean("f"))
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun saveListen(map: Map<String, Listen>) {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, JSONObject().put("p", v.pos).put("d", v.dur).put("f", v.played)) }
        write(listenFile, o.toString())
    }

    // ----- Podcasts seguidos -----

    fun loadFollows(): List<Follow> {
        if (!followFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(followFile.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Follow(
                    o.getString("feedUrl"),
                    o.optString("name"),
                    o.optString("author"),
                    o.optString("artwork"),
                    strings(o.optJSONArray("known")),
                    strings(o.optJSONArray("new"))
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveFollows(list: List<Follow>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("feedUrl", it.feedUrl)
                    .put("name", it.name)
                    .put("author", it.author)
                    .put("artwork", it.artwork)
                    .put("known", JSONArray(it.known.toList()))
                    .put("new", JSONArray(it.newIds.toList()))
            )
        }
        write(followFile, arr.toString())
    }

    private fun strings(a: JSONArray?): Set<String> {
        if (a == null) return emptySet()
        val s = HashSet<String>()
        for (i in 0 until a.length()) s.add(a.getString(i))
        return s
    }
}
