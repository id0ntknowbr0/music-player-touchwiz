package dev.s4music.player.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class LyricLine(val timeMs: Long?, val text: String)

data class Lyrics(val lines: List<LyricLine>, val synced: Boolean, val source: Source) {
    enum class Source { Saved, Embedded }

    /** Index of the line being sung at [positionMs], or -1. */
    fun currentIndex(positionMs: Long): Int {
        if (!synced) return -1
        var idx = -1
        for (i in lines.indices) {
            val t = lines[i].timeMs ?: continue
            if (t <= positionMs) idx = i else break
        }
        return idx
    }

    companion object {
        private val TIME = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        private val META = Regex("""^\[(ar|ti|al|au|by|length|offset|re|ve|#):.*]$""", RegexOption.IGNORE_CASE)

        /** Parses LRC (synced) or plain text lyrics. */
        fun parse(text: String, source: Source): Lyrics? {
            val raw = text.replace("\r\n", "\n").replace('\r', '\n').trim()
            if (raw.isEmpty()) return null
            val timed = ArrayList<LyricLine>()
            raw.lines().forEach { line ->
                val trimmed = line.trim()
                if (META.matches(trimmed)) return@forEach
                val stamps = TIME.findAll(trimmed).toList()
                if (stamps.isEmpty()) return@forEach
                val words = trimmed.substring(stamps.last().range.last + 1).trim()
                stamps.forEach { m ->
                    val (mm, ss, frac) = m.destructured
                    val fracMs = when (frac.length) { 0 -> 0; 1 -> frac.toInt() * 100; 2 -> frac.toInt() * 10; else -> frac.toInt() }
                    timed += LyricLine(mm.toLong() * 60_000 + ss.toLong() * 1000 + fracMs, words)
                }
            }
            return if (timed.isNotEmpty()) {
                Lyrics(timed.sortedBy { it.timeMs }, synced = true, source = source)
            } else {
                Lyrics(raw.lines().filterNot { META.matches(it.trim()) }.map { LyricLine(null, it) }, synced = false, source = source)
            }
        }
    }
}

/** Lyrics the user imported or downloaded, stored per song in app storage. */
object LyricsRepository {
    private const val MAX_BYTES = 512 * 1024
    private fun file(context: Context, songId: Long) = File(File(context.filesDir, "lyrics").apply { mkdirs() }, "$songId.lrc")

    suspend fun load(context: Context, song: Song): Lyrics? = withContext(Dispatchers.IO) {
        val saved = file(context, song.id).takeIf { it.exists() }?.readText()
        saved?.let { Lyrics.parse(it, Lyrics.Source.Saved) }
            ?: FileOps.readEmbeddedLyrics(song)?.let { Lyrics.parse(it, Lyrics.Source.Embedded) }
    }

    suspend fun save(context: Context, songId: Long, text: String) = withContext(Dispatchers.IO) {
        file(context, songId).writeText(text)
    }

    suspend fun delete(context: Context, songId: Long) = withContext(Dispatchers.IO) {
        file(context, songId).delete()
    }

    /** Reads a user-picked .lrc / .txt file. */
    suspend fun importFrom(context: Context, songId: Long, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        // Read at most MAX_BYTES + 1 (InputStream.readNBytes needs API 33).
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (out.size() <= MAX_BYTES) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: return@withContext false
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return@withContext false
        val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        if (Lyrics.parse(text, Lyrics.Source.Saved) == null) return@withContext false
        save(context, songId, text)
        true
    }

    // ---- Online lookup (LRCLIB), only after the user has agreed ----

    fun hasWebConsent() = AppSettings.current.lyricsOnline

    fun setWebConsent(allowed: Boolean) = AppSettings.update { it.copy(lyricsOnline = allowed) }

    sealed interface WebResult {
        data object Found : WebResult
        data object NotFound : WebResult
        data object NotAllowed : WebResult
        data class Failed(val message: String?) : WebResult
    }

    /** Looks the song up on lrclib.net and saves the result. Sends only title, artist, album and length. */
    suspend fun fetchFromWeb(context: Context, song: Song): WebResult = withContext(Dispatchers.IO) {
        if (!hasWebConsent()) return@withContext WebResult.NotAllowed
        try {
            val exact = getJson(
                "https://lrclib.net/api/get?track_name=${enc(song.title)}&artist_name=${enc(song.artist)}" +
                    "&album_name=${enc(song.album)}&duration=${song.durationMs / 1000}"
            )?.let { JSONObject(it) }
            val hit = exact ?: getJson(
                "https://lrclib.net/api/search?track_name=${enc(song.title)}&artist_name=${enc(song.artist)}"
            )?.let { body ->
                val arr = JSONArray(body)
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .sortedByDescending { !it.optString("syncedLyrics").isNullOrBlank() && it.optString("syncedLyrics") != "null" }
                    .firstOrNull()
            }
            val text = hit?.let { o ->
                o.optString("syncedLyrics").takeUnless { it.isBlank() || it == "null" }
                    ?: o.optString("plainLyrics").takeUnless { it.isBlank() || it == "null" }
            } ?: return@withContext WebResult.NotFound
            save(context, song.id, text)
            WebResult.Found
        } catch (e: Exception) {
            WebResult.Failed(e.message)
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Returns the body for HTTP 200, null for 404. */
    private fun getJson(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "MusicPlayer/1.0 (Android personal app)")
        conn.setRequestProperty("Accept", "application/json")
        return try {
            when (conn.responseCode) {
                200 -> conn.inputStream.bufferedReader().use { it.readText() }
                404 -> null
                else -> throw RuntimeException("HTTP ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }
}
