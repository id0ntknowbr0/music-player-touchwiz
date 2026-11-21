package dev.s4music.player.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import dev.s4music.player.R
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
    /** Composer tag; empty when the file has none. */
    val composer: String = "",
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
    /** Songs grouped by composer; songs without a composer tag aren't listed. */
    val composers: List<SongGroup> = emptyList(),
) {
    val byId: Map<Long, Song> = songs.associateBy { it.id }

    companion object {
        val EMPTY = Library(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }
}

object MediaStoreRepository {
    // Labels for missing tags, set from string resources by [initLabels] so they can be translated.
    var UNKNOWN_ARTIST = "Unknown artist"; private set
    var UNKNOWN_ALBUM = "Unknown album"; private set
    var UNKNOWN_GENRE = "Unknown genre"; private set
    private var unknownTitle = "Unknown"
    private var internalStorage = "Internal storage"

    fun initLabels(context: Context) {
        UNKNOWN_ARTIST = context.getString(R.string.unknown_artist)
        UNKNOWN_ALBUM = context.getString(R.string.unknown_album)
        UNKNOWN_GENRE = context.getString(R.string.unknown_genre)
        unknownTitle = context.getString(R.string.unknown_title)
        internalStorage = context.getString(R.string.internal_storage)
    }

    private val byName = Comparator<String> { a, b -> a.compareTo(b, ignoreCase = true) }
    private val trackOrder = compareBy<Song>({ it.track }, { it.title.lowercase() })

    /** The whole library, unfiltered (used to restore the saved queue). */
    suspend fun load(context: Context): Library = build(loadSongs(context), SongSort.TITLE)

    /** Every music file MediaStore knows about, before the user's library filters. */
    suspend fun loadSongs(context: Context): List<Song> = withContext(Dispatchers.IO) {
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
            MediaStore.Audio.Media.COMPOSER,
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
            val iComposer = c.getColumnIndexOrThrow(MediaStore.Audio.Media.COMPOSER)
            while (c.moveToNext()) {
                val artist = c.getString(iArtist).cleanTag() ?: UNKNOWN_ARTIST
                songs += Song(
                    id = c.getLong(iId),
                    title = c.getString(iTitle).cleanTag() ?: unknownTitle,
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
                    composer = c.getString(iComposer).cleanTag().orEmpty(),
                )
            }
        }
        songs
    }

    /** Applies the "Ignore short tracks" and scan-folder settings. */
    fun filter(songs: List<Song>, settings: Settings): List<Song> {
        val minMs = settings.minDurationSec * 1000L
        return songs.filter { s ->
            // Duration 0 means MediaStore doesn't know it yet; keep those rather than hide real music.
            (minMs == 0L || s.durationMs <= 0 || s.durationMs >= minMs) && !settings.isExcluded(s.folder)
        }
    }

    fun songComparator(sort: SongSort): Comparator<Song> = when (sort) {
        SongSort.TITLE -> compareBy(byName) { it.title }
        SongSort.ARTIST -> compareBy<Song, String>(byName) { it.artist }.thenBy(byName) { it.title }
        SongSort.ALBUM -> compareBy<Song, String>(byName) { it.album }.then(trackOrder)
        SongSort.DATE_ADDED -> compareByDescending<Song> { it.dateAddedSec }.thenBy(byName) { it.title }
    }

    private fun String?.cleanTag(): String? =
        this?.trim()?.takeUnless { it.isEmpty() || it == "<unknown>" }

    fun build(all: List<Song>, sort: SongSort): Library {
        val songOrder = songComparator(sort)
        val songs = all.sortedWith(songOrder)
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
            SongGroup(g, g, list.sortedWith(songOrder))
        }.sortedWith(compareBy(byName) { it.name })
        val folders = all.groupBy { it.folder }.map { (path, list) ->
            SongGroup(path, path.substringAfterLast('/').ifEmpty { internalStorage }, list.sortedWith(songOrder))
        }.sortedWith(compareBy(byName) { it.name })
        val composers = all.filter { it.composer.isNotEmpty() }.groupBy { it.composer.lowercase() }.map { (key, list) ->
            SongGroup(key, list.first().composer, list.sortedWith(songOrder))
        }.sortedWith(compareBy(byName) { it.name })
        return Library(songs, albums, artists, genres, folders, composers)
    }
}
