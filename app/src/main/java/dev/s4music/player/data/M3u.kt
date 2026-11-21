package dev.s4music.player.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * .m3u / .m3u8 playlist import and export through the Storage Access Framework.
 * Exports use absolute paths, which other Android players and most PC players understand.
 */
object M3u {
    const val MIME = "audio/x-mpegurl"
    private const val MAX_BYTES = 4 * 1024 * 1024

    data class Entry(val location: String, val title: String?, val artist: String?)
    data class Parsed(val name: String, val entries: List<Entry>)
    data class Resolved(val songIds: List<Long>, val unmatched: List<String>)

    // ---- Export ----

    fun render(songs: List<Song>): String = buildString {
        append("#EXTM3U\n")
        songs.forEach { s ->
            append("#EXTINF:").append(s.durationMs / 1000).append(',')
            append(s.artist).append(" - ").append(s.title).append('\n')
            append(s.path.ifEmpty { s.uri.toString() }).append('\n')
        }
    }

    /** Writes one playlist to a document the user created with ACTION_CREATE_DOCUMENT. */
    suspend fun writeTo(context: Context, uri: Uri, songs: List<Song>) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(render(songs).toByteArray(Charsets.UTF_8)) }
    }

    /** Writes each playlist as "<name>.m3u8" into a folder the user picked. Returns how many were written. */
    suspend fun writeAllTo(context: Context, treeUri: Uri, playlists: List<Pair<String, List<Song>>>): Int =
        withContext(Dispatchers.IO) {
            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            playlists.count { (name, songs) ->
                runCatching {
                    val doc = DocumentsContract.createDocument(context.contentResolver, parent, MIME, fileNameFor(name))
                        ?: return@runCatching false
                    context.contentResolver.openOutputStream(doc, "wt")!!.use {
                        it.write(render(songs).toByteArray(Charsets.UTF_8))
                    }
                    true
                }.getOrDefault(false)
            }
        }

    fun fileNameFor(playlistName: String) =
        playlistName.map { if (it in "/\\:*?\"<>|" || it.code < 32) '_' else it }.joinToString("").trim().ifEmpty { "playlist" } + ".m3u8"

    // ---- Import ----

    suspend fun read(context: Context, uri: Uri, fallbackName: String): Parsed? = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }.orEmpty().substringBeforeLast('.').ifBlank { fallbackName }
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (out.size() <= MAX_BYTES) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: return@withContext null
        if (bytes.size > MAX_BYTES) return@withContext null
        parse(name, decode(bytes))
    }

    /** UTF-8 if it is valid UTF-8 (all .m3u8 and most modern .m3u), otherwise Windows-1252 (old PC .m3u). */
    private fun decode(bytes: ByteArray): String {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
        return text.removePrefix("\uFEFF")
    }

    fun parse(name: String, text: String): Parsed? {
        val entries = ArrayList<Entry>()
        var pendingTitle: String? = null
        var pendingArtist: String? = null
        text.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val info = line.substringAfter(',', "").trim()
                    val sep = info.indexOf(" - ")
                    if (sep > 0) {
                        pendingArtist = info.substring(0, sep).trim()
                        pendingTitle = info.substring(sep + 3).trim()
                    } else {
                        pendingArtist = null
                        pendingTitle = info.ifEmpty { null }
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    entries += Entry(line, pendingTitle, pendingArtist)
                    pendingTitle = null; pendingArtist = null
                }
            }
        }
        return if (entries.isEmpty()) null else Parsed(name, entries)
    }

    /**
     * Matches playlist entries to library songs: by the longest matching tail of the path
     * (so "C:\Music\Artist\a.mp3", "../Artist/a.mp3" and "/sdcard/Music/Artist/a.mp3" all work),
     * then by the #EXTINF artist and title.
     */
    fun resolve(entries: List<Entry>, songs: List<Song>): Resolved {
        val byFileName = songs.filter { it.path.isNotEmpty() }.groupBy { segments(it.path).last() }
        val byTitle = songs.groupBy { it.title.lowercase() }
        val ids = ArrayList<Long>()
        val unmatched = ArrayList<String>()
        for (e in entries) {
            val segs = segments(normalizeLocation(e.location))
            val candidates = segs.lastOrNull()?.let { byFileName[it] }.orEmpty()
            val byPath = candidates.maxByOrNull { matchingTail(segs, segments(it.path)) }
            val song = byPath ?: e.title?.let { t ->
                byTitle[t.lowercase()]?.let { same ->
                    same.firstOrNull { e.artist != null && it.artist.equals(e.artist, ignoreCase = true) }
                        ?: same.singleOrNull()
                }
            }
            if (song != null) ids += song.id else unmatched += e.location
        }
        return Resolved(ids, unmatched)
    }

    private fun normalizeLocation(raw: String): String {
        val s = raw.trim()
        if (s.startsWith("file:", ignoreCase = true)) return Uri.parse(s).path ?: s
        return s.replace('\\', '/')
    }

    private fun segments(path: String) = path.lowercase().split('/').filter { it.isNotEmpty() && it != "." && it != ".." }

    private fun matchingTail(a: List<String>, b: List<String>): Int {
        var n = 0
        while (n < a.size && n < b.size && a[a.size - 1 - n] == b[b.size - 1 - n]) n++
        return n
    }
}
