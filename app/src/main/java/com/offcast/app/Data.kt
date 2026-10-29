package com.offcast.app

import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Podcast(val name: String, val author: String, val artwork: String, val feedUrl: String)

data class Episode(val title: String, val audioUrl: String, val date: String, val duration: String)

data class Saved(val id: String, val title: String, val podcast: String, val artwork: String, val path: String)

data class FeedData(val title: String, val image: String, val episodes: List<Episode>)

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

    /** Busca el podcast en el directorio público de Apple y devuelve los feeds RSS. */
    fun search(query: String): List<Podcast> {
        val json = JSONObject(
            Net.text("https://itunes.apple.com/search?media=podcast&entity=podcast&limit=8&term=" + Net.enc(query))
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
    fun load(feedUrl: String): FeedData {
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
                        }
                        "title" -> {
                            val t = p.nextText().trim()
                            if (inItem) title = t else if (channelTitle.isEmpty()) channelTitle = t
                        }
                        "enclosure" -> if (inItem) url = p.getAttributeValue(null, "url") ?: ""
                        "pubDate" -> if (inItem) date = shortDate(p.nextText())
                        "itunes:duration" -> if (inItem) dur = p.nextText().trim()
                        "itunes:image" -> if (!inItem && channelImage.isEmpty()) {
                            channelImage = p.getAttributeValue(null, "href") ?: ""
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG && p.name == "item") {
                    inItem = false
                    if (url.isNotEmpty()) {
                        eps.add(Episode(title.ifEmpty { "Sin título" }, url, date, formatDuration(dur)))
                    }
                }
                ev = p.next()
            }
            return FeedData(channelTitle, channelImage, eps.take(150))
        } finally {
            c.disconnect()
        }
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

class Store(dir: File) {
    private val file = File(dir, "library.json")

    fun load(): List<Saved> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
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
        file.writeText(arr.toString())
    }
}
