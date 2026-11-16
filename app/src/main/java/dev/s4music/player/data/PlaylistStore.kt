package dev.s4music.player.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Playlist(val id: Long, val name: String, val songIds: List<Long>)

/**
 * App-private playlists stored as JSON. (MediaStore playlists are effectively
 * read-only for third-party apps on Android 11+, so we keep our own.)
 */
object PlaylistStore {
    private lateinit var file: File
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "playlists.json")
        _playlists.value = runCatching { read() }.getOrDefault(emptyList())
    }

    fun create(name: String, songIds: List<Long> = emptyList()): Playlist {
        val p = Playlist(System.currentTimeMillis(), name.trim(), songIds.distinct())
        mutate { it + p }
        return p
    }

    fun rename(id: Long, name: String) = mutate { list ->
        list.map { if (it.id == id) it.copy(name = name.trim()) else it }
    }

    fun delete(id: Long) = mutate { list -> list.filterNot { it.id == id } }

    /** Adds songs not already in the playlist. Returns how many were added. */
    fun add(id: Long, songIds: List<Long>): Int {
        var added = 0
        mutate { list ->
            list.map { p ->
                if (p.id != id) p else {
                    val existing = p.songIds.toSet()
                    val fresh = songIds.distinct().filterNot { it in existing }
                    added = fresh.size
                    p.copy(songIds = p.songIds + fresh)
                }
            }
        }
        return added
    }

    fun removeAt(id: Long, index: Int) = mutate { list ->
        list.map { p ->
            if (p.id == id && index in p.songIds.indices) {
                p.copy(songIds = p.songIds.toMutableList().apply { removeAt(index) })
            } else p
        }
    }

    /** Removes deleted songs from every playlist. */
    fun removeSongsEverywhere(songIds: Set<Long>) {
        if (_playlists.value.none { p -> p.songIds.any { it in songIds } }) return
        mutate { list -> list.map { p -> p.copy(songIds = p.songIds.filterNot { it in songIds }) } }
    }

    private fun mutate(block: (List<Playlist>) -> List<Playlist>) {
        _playlists.update(block)
        write(_playlists.value)
    }

    private fun read(): List<Playlist> {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val ids = o.getJSONArray("songs")
            Playlist(o.getLong("id"), o.getString("name"), (0 until ids.length()).map { ids.getLong(it) })
        }
    }

    @Synchronized
    private fun write(list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("songs", JSONArray(p.songIds)))
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }
}
