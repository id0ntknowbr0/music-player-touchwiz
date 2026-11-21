package dev.s4music.player.playback

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dev.s4music.player.R
import dev.s4music.player.data.AppSettings
import dev.s4music.player.data.Favourites
import dev.s4music.player.data.Library
import dev.s4music.player.data.MediaStoreRepository
import dev.s4music.player.data.PlayStats
import dev.s4music.player.data.PlaylistStore
import dev.s4music.player.data.Song

/**
 * The browse tree for Android Auto and other media browsers:
 *
 *     root ─ Songs ─ songs
 *          ├ Albums ─ album:<id> ─ songs
 *          ├ Artists ─ artist:<id> ─ songs
 *          ├ Playlists ─ Favourites, Most played, Recently added, playlist:<id> ─ songs
 *          └ Recently played ─ songs
 *
 * A song inside a list has the ID "<list id>|<song id>", so picking it can queue the whole list
 * from that song ([expand]). In the play queue songs use their plain ID, as everywhere else.
 */
class BrowseTree(private val context: Context) {
    private var cached: Library? = null
    private var cachedAt = 0L

    private suspend fun library(): Library {
        val now = SystemClock.elapsedRealtime()
        cached?.takeIf { now - cachedAt < CACHE_MS }?.let { return it }
        val s = AppSettings.current
        val lib = MediaStoreRepository.build(
            MediaStoreRepository.filter(MediaStoreRepository.loadSongs(context), s), s.songSort)
        cached = lib
        cachedAt = now
        return lib
    }

    fun root(): MediaItem = folder(ROOT, context.getString(R.string.app_name), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    suspend fun children(parentId: String): List<MediaItem>? {
        val lib = library()
        return when {
            parentId == ROOT -> listOf(
                folder(SONGS, R.string.tab_songs, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                folder(ALBUMS, R.string.tab_albums, MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                folder(ARTISTS, R.string.tab_artists, MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
                folder(PLAYLISTS, R.string.tab_playlists, MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                folder(RECENT, R.string.recently_played, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            )
            parentId == ALBUMS -> lib.albums.map { a ->
                folder("$ALBUM${a.id}", a.title, MediaMetadata.MEDIA_TYPE_ALBUM, subtitle = a.artist, art = artUri(a.id))
            }
            parentId == ARTISTS -> lib.artists.map { a ->
                folder("$ARTIST${a.id}", a.name, MediaMetadata.MEDIA_TYPE_ARTIST,
                    art = a.songs.firstOrNull()?.albumId?.let(::artUri))
            }
            parentId == PLAYLISTS -> listOf(
                folder(FAVOURITES, R.string.favourites, MediaMetadata.MEDIA_TYPE_PLAYLIST),
                folder(MOST_PLAYED, R.string.most_played, MediaMetadata.MEDIA_TYPE_PLAYLIST),
                folder(RECENTLY_ADDED, R.string.recently_added, MediaMetadata.MEDIA_TYPE_PLAYLIST),
            ) + PlaylistStore.playlists.value.map { p ->
                folder("$PLAYLIST${p.id}", p.name, MediaMetadata.MEDIA_TYPE_PLAYLIST)
            }
            else -> songsOf(parentId, lib)?.map { it.browseItem(parentId) }
        }
    }

    /** The songs a list ID stands for, or null if it isn't a song list. */
    private suspend fun songsOf(listId: String, lib: Library): List<Song>? = when {
        listId == SONGS -> lib.songs
        listId == RECENT -> PlayStats.stats.value.entries.sortedByDescending { it.value.lastPlayed }
            .mapNotNull { lib.byId[it.key] }.take(SMART_LIMIT)
        listId == FAVOURITES -> Favourites.ids.value.mapNotNull { lib.byId[it] }
        listId == MOST_PLAYED -> PlayStats.stats.value.entries.filter { it.value.count > 0 }
            .sortedByDescending { it.value.count }.mapNotNull { lib.byId[it.key] }.take(SMART_LIMIT)
        listId == RECENTLY_ADDED -> lib.songs.sortedByDescending { it.dateAddedSec }.take(SMART_LIMIT)
        listId.startsWith(ALBUM) -> lib.albums.firstOrNull { it.id == listId.removePrefix(ALBUM).toLongOrNull() }?.songs
        listId.startsWith(ARTIST) -> lib.artists.firstOrNull { it.id == listId.removePrefix(ARTIST).toLongOrNull() }?.songs
        listId.startsWith(PLAYLIST) -> PlaylistStore.playlists.value
            .firstOrNull { it.id == listId.removePrefix(PLAYLIST).toLongOrNull() }?.songIds?.mapNotNull { lib.byId[it] }
        listId.startsWith(SEARCH) -> search(listId.removePrefix(SEARCH))
        else -> null
    }

    suspend fun item(id: String): MediaItem? {
        if (id == ROOT) return root()
        val songId = id.substringAfterLast('|').toLongOrNull()
        if (songId != null) return library().byId[songId]?.browseItem(id.substringBeforeLast('|', ""))
        // A folder: find it in its parent's children.
        val parent = when {
            id.startsWith(ALBUM) -> ALBUMS
            id.startsWith(ARTIST) -> ARTISTS
            id.startsWith(PLAYLIST) || id in setOf(FAVOURITES, MOST_PLAYED, RECENTLY_ADDED) -> PLAYLISTS
            else -> ROOT
        }
        return children(parent)?.firstOrNull { it.mediaId == id }
    }

    suspend fun search(query: String): List<Song> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val words = q.lowercase().split(Regex("\\s+"))
        // Every word must appear in the title, artist or album ("queen bohemian" finds the song).
        return library().songs.filter { s ->
            val text = "${s.title} ${s.artist} ${s.album}".lowercase()
            words.all { it in text }
        }
    }

    suspend fun searchItems(query: String) = search(query).map { it.browseItem(SEARCH + query) }

    /**
     * Turns what a browser asked to play into a queue: a song picked in a list plays that whole list
     * from the song; a voice search ("play …") plays its results.
     */
    suspend fun expand(items: List<MediaItem>): Pair<List<MediaItem>, Int>? {
        val single = items.singleOrNull() ?: return null
        val query = single.requestMetadata.searchQuery
        if (query != null && single.mediaId.isEmpty()) {
            val found = search(query)
            return if (found.isEmpty()) null else found.map { it.toMediaItem() } to 0
        }
        val id = single.mediaId
        if ('|' !in id) {
            // A whole album / playlist chosen as one item.
            val songs = songsOf(id, library()) ?: return null
            return if (songs.isEmpty()) null else songs.map { it.toMediaItem() } to 0
        }
        val songId = id.substringAfterLast('|').toLongOrNull() ?: return null
        val songs = songsOf(id.substringBeforeLast('|'), library()).orEmpty()
        val index = songs.indexOfFirst { it.id == songId }
        if (index < 0) return library().byId[songId]?.let { listOf(it.toMediaItem()) to 0 }
        return songs.map { it.toMediaItem() } to index
    }

    /** Maps browse IDs ("list|song") back to plain queue items. */
    suspend fun resolve(item: MediaItem): MediaItem {
        val id = item.mediaId
        if ('|' in id) {
            id.substringAfterLast('|').toLongOrNull()?.let { library().byId[it] }?.let { return it.toMediaItem() }
        }
        return resolveMediaItem(item)
    }

    private fun Song.browseItem(parentId: String): MediaItem {
        val base = toMediaItem()
        return base.buildUpon()
            .setMediaId("$parentId|$id")
            .setMediaMetadata(base.mediaMetadata.buildUpon()
                .setArtworkUri(artUri(albumId))
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build())
            .build()
    }

    private fun folder(id: String, @StringRes title: Int, type: Int) = folder(id, context.getString(title), type)

    private fun folder(id: String, title: String, type: Int, subtitle: String? = null, art: Uri? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtist(subtitle)
                    .setArtworkUri(art)
                    .setIsBrowsable(true)
                    .setIsPlayable(id.startsWith(ALBUM) || id.startsWith(PLAYLIST) ||
                        id in setOf(FAVOURITES, MOST_PLAYED, RECENTLY_ADDED))
                    .setMediaType(type)
                    .build()
            )
            .build()

    private fun artUri(albumId: Long): Uri = ContentUris.withAppendedId(ALBUM_ART, albumId)

    companion object {
        const val ROOT = "root"
        private const val SONGS = "songs"
        private const val ALBUMS = "albums"
        private const val ARTISTS = "artists"
        private const val PLAYLISTS = "playlists"
        private const val RECENT = "recent"
        private const val FAVOURITES = "favourites"
        private const val MOST_PLAYED = "most_played"
        private const val RECENTLY_ADDED = "recently_added"
        private const val ALBUM = "album:"
        private const val ARTIST = "artist:"
        private const val PLAYLIST = "playlist:"
        private const val SEARCH = "search:"
        private const val SMART_LIMIT = 50
        private const val CACHE_MS = 30_000L
        private val ALBUM_ART: Uri = Uri.parse("content://media/external/audio/albumart")
    }
}
