package dev.s4music.player.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val artistId: Long,
    val album: String,
    val albumId: Long,
    val genre: String,
    val year: Int,
    val track: Int,
    val durationMs: Long,
    val folder: String,
    val dateAddedSec: Long,
    /** Absolute file path (readable through FUSE on Android 11+). */
    val path: String,
    /** File name including extension, e.g. "Track 01.mp3". */
    val displayName: String,
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
}

data class Album(val id: Long, val title: String, val artist: String, val year: Int, val songs: List<Song>)
data class Artist(val id: Long, val name: String, val albumCount: Int, val songs: List<Song>)

/** Generic named group of songs (genres, folders). */
data class SongGroup(val key: String, val name: String, val songs: List<Song>)

data class Library(
    val songs: List<Song>,
    val albums: List<Album>,
    val artists: List<Artist>,
    val genres: List<SongGroup>,
    val folders: List<SongGroup>,
) {
    val byId: Map<Long, Song> = songs.associateBy { it.id }

    companion object {
        val EMPTY = Library(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }
}

object MediaStoreRepository {
    const val UNKNOWN_ARTIST = "Unknown artist"
    const val UNKNOWN_ALBUM = "Unknown album"
    const val UNKNOWN_GENRE = "Unknown genre"

    private val byName = Comparator<String> { a, b -> a.compareTo(b, ignoreCase = true) }
    private val trackOrder = compareBy<Song>({ it.track }, { it.title.lowercase() })

    suspend fun load(context: Context): Library = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.GENRE,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )
        val songs = ArrayList<Song>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            null,
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iArtistId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val iGenre = c.getColumnIndexOrThrow(MediaStore.Audio.Media.GENRE)
            val iYear = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val iDuration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iPath = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            @Suppress("DEPRECATION")
            val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val iName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            while (c.moveToNext()) {
                val artist = c.getString(iArtist).cleanTag() ?: UNKNOWN_ARTIST
                songs += Song(
                    id = c.getLong(iId),
                    title = c.getString(iTitle).cleanTag() ?: "Unknown",
                    artist = artist,
                    artistId = c.getLong(iArtistId),
                    album = c.getString(iAlbum).cleanTag() ?: UNKNOWN_ALBUM,
                    albumId = c.getLong(iAlbumId),
                    genre = c.getString(iGenre).cleanTag() ?: UNKNOWN_GENRE,
                    year = c.getInt(iYear),
                    track = c.getInt(iTrack),
                    durationMs = c.getLong(iDuration),
                    folder = c.getString(iPath)?.trimEnd('/') ?: "",
                    dateAddedSec = c.getLong(iAdded),
                    path = c.getString(iData).orEmpty(),
                    displayName = c.getString(iName).orEmpty(),
                )
            }
        }
        build(songs)
    }

    private fun String?.cleanTag(): String? =
        this?.trim()?.takeUnless { it.isEmpty() || it == "<unknown>" }

    private fun build(all: List<Song>): Library {
        val songs = all.sortedWith(compareBy(byName) { it.title })
        val albums = all.groupBy { it.albumId }.map { (id, list) ->
            val sorted = list.sortedWith(trackOrder)
            Album(
                id = id,
                title = sorted.first().album,
                artist = list.groupingBy { it.artist }.eachCount().maxByOrNull { it.value }!!.key,
                year = list.maxOf { it.year },
                songs = sorted,
            )
        }.sortedWith(compareBy(byName) { it.title })
        val artists = all.groupBy { it.artistId }.map { (id, list) ->
            Artist(
                id = id,
                name = list.first().artist,
                albumCount = list.distinctBy { it.albumId }.size,
                songs = list.sortedWith(compareBy<Song>({ it.album.lowercase() }, { it.track })),
            )
        }.sortedWith(compareBy(byName) { it.name })
        val genres = all.groupBy { it.genre }.map { (g, list) ->
            SongGroup(g, g, list.sortedWith(compareBy(byName) { it.title }))
        }.sortedWith(compareBy(byName) { it.name })
        val folders = all.groupBy { it.folder }.map { (path, list) ->
            SongGroup(path, path.substringAfterLast('/').ifEmpty { "Internal storage" },
                list.sortedWith(compareBy(byName) { it.title }))
        }.sortedWith(compareBy(byName) { it.name })
        return Library(songs, albums, artists, genres, folders)
    }
}
