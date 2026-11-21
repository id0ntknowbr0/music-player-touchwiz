package dev.s4music.player.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.s4music.player.R
import dev.s4music.player.data.CoverStore
import dev.s4music.player.data.Favourites
import dev.s4music.player.data.FileOps
import dev.s4music.player.data.Library
import dev.s4music.player.data.M3u
import dev.s4music.player.data.PlayStats
import dev.s4music.player.data.Playlist
import dev.s4music.player.data.PlaylistStore
import dev.s4music.player.data.Song
import dev.s4music.player.data.TrackEdits
import kotlinx.coroutines.launch

// ---------- Navigation model ----------

sealed interface SongSource {
    data class Album(val id: Long) : SongSource
    data class Artist(val id: Long) : SongSource
    data class Genre(val key: String) : SongSource
    data class Folder(val key: String) : SongSource
    data class UserPlaylist(val id: Long) : SongSource
    data object MostPlayed : SongSource
    data object RecentlyPlayed : SongSource
    data object RecentlyAdded : SongSource
    data object Favourites : SongSource
}

sealed interface Screen {
    data object Library : Screen
    data object Search : Screen
    data class Songs(val title: String, val source: SongSource) : Screen
    data object Settings : Screen
}

private const val SMART_LIMIT = 50

fun SongSource.resolve(
    lib: Library,
    playlists: List<Playlist>,
    stats: Map<Long, PlayStats.Stat>,
    favourites: List<Long>,
): List<Song> =
    when (this) {
        is SongSource.Album -> lib.albums.firstOrNull { it.id == id }?.songs.orEmpty()
        is SongSource.Artist -> lib.artists.firstOrNull { it.id == id }?.songs.orEmpty()
        is SongSource.Genre -> lib.genres.firstOrNull { it.key == key }?.songs.orEmpty()
        is SongSource.Folder -> lib.folders.firstOrNull { it.key == key }?.songs.orEmpty()
        is SongSource.UserPlaylist -> playlists.firstOrNull { it.id == id }?.songIds?.mapNotNull { lib.byId[it] }.orEmpty()
        SongSource.MostPlayed -> stats.entries.filter { it.value.count > 0 }
            .sortedByDescending { it.value.count }.mapNotNull { lib.byId[it.key] }.take(SMART_LIMIT)
        SongSource.RecentlyPlayed -> stats.entries.sortedByDescending { it.value.lastPlayed }
            .mapNotNull { lib.byId[it.key] }.take(SMART_LIMIT)
        SongSource.RecentlyAdded -> lib.songs.sortedByDescending { it.dateAddedSec }.take(SMART_LIMIT)
        SongSource.Favourites -> favourites.mapNotNull { lib.byId[it] }
    }

enum class GlobalDialog { SoundAlive, PlaySpeed, SleepTimer }

/** Actions any screen can trigger; provided through [LocalUi]. */
class UiActions(
    val context: Context,
    val player: PlayerViewModel,
    val navigate: (Screen) -> Unit = {},
    val addToPlaylist: (List<Song>) -> Unit = {},
    val showDetails: (Song) -> Unit = {},
    val showDialog: (GlobalDialog) -> Unit = {},
    val closePlayer: () -> Unit = {},
    val editSong: (Song) -> Unit = {},
    val deleteSongs: (List<Song>) -> Unit = {},
    val changeAlbumCover: (List<Song>) -> Unit = {},
    val exportPlaylist: (name: String, songs: List<Song>) -> Unit = { _, _ -> },
    val exportAllPlaylists: () -> Unit = {},
    val importPlaylists: () -> Unit = {},
) {
    private fun str(@StringRes id: Int) = context.getString(id)

    fun toggleFavourite(song: Song) {
        val added = Favourites.toggle(song.id)
        Toast.makeText(context, if (added) R.string.added_to_favourites else R.string.removed_from_favourites,
            Toast.LENGTH_SHORT).show()
    }

    /** [isFavourite] comes from the caller's collected state so the label stays current. */
    fun songMenu(song: Song, isFavourite: Boolean, extra: List<MenuAction> = emptyList()) = listOf(
        MenuAction(str(R.string.play_next)) { player.playNext(listOf(song)) },
        MenuAction(str(R.string.add_to_queue)) { player.addToQueue(listOf(song)) },
        MenuAction(str(R.string.add_to_playlist)) { addToPlaylist(listOf(song)) },
        MenuAction(str(if (isFavourite) R.string.remove_from_favourites else R.string.add_to_favourites)) {
            toggleFavourite(song)
        },
        MenuAction(str(R.string.edit_track_info)) { editSong(song) },
        MenuAction(str(R.string.details)) { showDetails(song) },
    ) + extra + MenuAction(str(R.string.delete)) { deleteSongs(listOf(song)) }

    fun groupMenu(
        songs: () -> List<Song>,
        extra: List<MenuAction> = emptyList(),
        allowDelete: Boolean = true,
        allowAddToPlaylist: Boolean = true,
    ) = listOfNotNull(
        MenuAction(str(R.string.play)) { player.playSongs(songs()) },
        MenuAction(str(R.string.shuffle)) { player.playSongs(songs(), shuffle = true) },
        MenuAction(str(R.string.play_next)) { player.playNext(songs()) },
        MenuAction(str(R.string.add_to_queue)) { player.addToQueue(songs()) },
        if (allowAddToPlaylist) MenuAction(str(R.string.add_to_playlist)) { addToPlaylist(songs()) } else null,
    ) + extra + if (allowDelete) listOf(MenuAction(str(R.string.delete_tracks)) { deleteSongs(songs()) }) else emptyList()
}

val LocalUi = staticCompositionLocalOf<UiActions> { error("UiActions not provided") }

// ---------- Root ----------

@Composable
fun S4MusicApp(libraryVm: LibraryViewModel, playerVm: PlayerViewModel, openPlayer: MutableState<Boolean>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library by libraryVm.library.collectAsStateWithLifecycle()
    val loading by libraryVm.loading.collectAsStateWithLifecycle()
    val playlists by PlaylistStore.playlists.collectAsStateWithLifecycle()
    val stats by PlayStats.stats.collectAsStateWithLifecycle()
    val favourites by Favourites.ids.collectAsStateWithLifecycle()
    val ps by playerVm.state.collectAsStateWithLifecycle()

    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Library)) }
    var addToPlaylist by remember { mutableStateOf<List<Song>?>(null) }
    var details by remember { mutableStateOf<Song?>(null) }
    var editing by remember { mutableStateOf<Song?>(null) }
    var dialog by remember { mutableStateOf<GlobalDialog?>(null) }

    // ---- System "allow modify / delete" dialogs (Android 11+ scoped storage) ----
    var afterGrant by remember { mutableStateOf<(() -> Unit)?>(null) }
    val grantLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) afterGrant?.invoke()
        afterGrant = null
    }

    fun requestDelete(songs: List<Song>) {
        if (songs.isEmpty()) return
        val ids = songs.map { it.id }.toSet()
        val request = MediaStore.createDeleteRequest(context.contentResolver, songs.map { it.uri })
        afterGrant = {
            playerVm.removeSongs(ids)
            PlaylistStore.removeSongsEverywhere(ids)
            Favourites.removeAll(ids)
            libraryVm.reload(debounceMs = 300)
            Toast.makeText(context, context.resources.getQuantityString(R.plurals.tracks_deleted, songs.size, songs.size),
                Toast.LENGTH_SHORT).show()
        }
        grantLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }

    /**
     * Writes [edits] to [song] and, if a [coverUri] was picked, embeds that cover in the song
     * (or in every song of its album when [wholeAlbum]). One system prompt covers all files.
     */
    fun requestEdit(song: Song, edits: TrackEdits?, coverUri: Uri?, wholeAlbum: Boolean) {
        val albumSongs = library.albums.firstOrNull { it.id == song.albumId }?.songs ?: listOf(song)
        val targets = if (coverUri != null && wholeAlbum) (listOf(song) + albumSongs).distinctBy { it.id } else listOf(song)
        val request = MediaStore.createWriteRequest(context.contentResolver, targets.map { it.uri })
        afterGrant = {
            scope.launch {
                val touched = targets.map { it.id }.toSet()
                val isCurrent = ps.currentSongId in touched
                val wasPlaying = ps.isPlaying
                if (isCurrent) playerVm.pause()   // don't rewrite a file mid-read
                var failed = 0
                var mainUpdated: Song = song
                val result = runCatching {
                    val cover = coverUri?.let { FileOps.prepareCover(context, it) }
                    if (edits != null) {
                        val newPath = FileOps.apply(context, song, edits, cover)
                        mainUpdated = song.copy(
                            title = edits.title, artist = edits.artist.ifEmpty { song.artist },
                            album = edits.album.ifEmpty { song.album }, path = newPath,
                            displayName = newPath.substringAfterLast('/'),
                        )
                    } else if (cover != null) {
                        FileOps.setCover(context, song, cover)   // "Change album cover"
                    }
                    if (cover != null) {
                        targets.filter { it.id != song.id }.forEach { other ->
                            if (runCatching { FileOps.setCover(context, other, cover) }.isFailure) failed++
                        }
                        if (wholeAlbum || albumSongs.size == 1) {
                            CoverStore.saveForAlbum(context, song.albumId, albumSongs.map { it.id }, cover)
                        } else {
                            CoverStore.saveForSong(context, song.id, cover)
                        }
                    }
                }
                result.onSuccess {
                    playerVm.updateSongs(targets.map { if (it.id == song.id) mainUpdated else it },
                        resume = isCurrent && wasPlaying)
                    val what = context.getString(if (edits == null) R.string.cover_changed else R.string.track_info_saved)
                    Toast.makeText(context,
                        if (failed > 0) context.resources.getQuantityString(R.plurals.files_not_changed, failed, what, failed)
                        else what,
                        Toast.LENGTH_SHORT).show()
                }.onFailure { e ->
                    if (isCurrent && wasPlaying) playerVm.togglePlay()
                    Toast.makeText(context,
                        context.getString(R.string.couldnt_save, e.message ?: context.getString(R.string.unsupported_file)),
                        Toast.LENGTH_LONG).show()
                }
                libraryVm.reload(debounceMs = 1200)
            }
        }
        grantLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }

    // "Change album cover": pick an image, then embed it in every song of the album.
    var coverTargets by remember { mutableStateOf<List<Song>>(emptyList()) }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val first = coverTargets.firstOrNull()
        if (uri != null && first != null) requestEdit(first, null, uri, wholeAlbum = true)
        coverTargets = emptyList()
    }

    // ---- Playlist export / import (.m3u8 through the Storage Access Framework) ----
    var exportSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    val exportOne = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(M3u.MIME)) { uri ->
        val songs = exportSongs
        exportSongs = emptyList()
        if (uri != null) scope.launch {
            val msg = runCatching { M3u.writeTo(context, uri, songs) }.fold(
                onSuccess = { context.resources.getQuantityString(R.plurals.export_done, 1, 1) },
                onFailure = { context.getString(R.string.export_failed, it.message.orEmpty()) },
            )
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
    fun allPlaylistsForExport(): List<Pair<String, List<Song>>> =
        PlaylistStore.playlists.value.map { p -> p.name to p.songIds.mapNotNull { library.byId[it] } } +
            Favourites.ids.value.mapNotNull { library.byId[it] }.takeIf { it.isNotEmpty() }
                ?.let { listOf(context.getString(R.string.favourites) to it) }.orEmpty()
    val exportAll = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) scope.launch {
            val n = runCatching { M3u.writeAllTo(context, tree, allPlaylistsForExport()) }.getOrDefault(0)
            Toast.makeText(context, context.resources.getQuantityString(R.plurals.export_done, n, n), Toast.LENGTH_SHORT).show()
        }
    }
    var importReport by remember { mutableStateOf<List<ImportResult>?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val fallback = context.getString(R.string.imported_playlist_default)
            importReport = uris.map { uri ->
                val parsed = runCatching { M3u.read(context, uri, fallback) }.getOrNull()
                    ?: return@map ImportResult(uri.lastPathSegment?.substringAfterLast('/') ?: fallback, null, emptyList())
                val resolved = M3u.resolve(parsed.entries, libraryVm.rawSongs)
                if (resolved.songIds.isNotEmpty()) PlaylistStore.create(uniquePlaylistName(parsed.name), resolved.songIds)
                ImportResult(parsed.name, parsed.entries.size to resolved.songIds.size, resolved.unmatched)
            }
        }
    }

    val ui = remember(playerVm) {
        UiActions(
            context = context,
            player = playerVm,
            navigate = { stack = stack + it },
            addToPlaylist = { addToPlaylist = it },
            showDetails = { details = it },
            showDialog = { dialog = it },
            closePlayer = { openPlayer.value = false },
            editSong = { editing = it },
            deleteSongs = { requestDelete(it) },
            changeAlbumCover = { songs ->
                if (songs.isNotEmpty()) {
                    coverTargets = songs
                    coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            },
            exportPlaylist = { name, songs ->
                exportSongs = songs
                exportOne.launch(M3u.fileNameFor(name))
            },
            exportAllPlaylists = {
                if (allPlaylistsForExport().isEmpty()) {
                    Toast.makeText(context, R.string.nothing_to_export, Toast.LENGTH_SHORT).show()
                } else exportAll.launch(null)
            },
            importPlaylists = { importer.launch(arrayOf("*/*")) },
        )
    }

    BackHandler(enabled = !openPlayer.value && stack.size > 1) { stack = stack.dropLast(1) }
    BackHandler(enabled = openPlayer.value) { openPlayer.value = false }

    CompositionLocalProvider(LocalUi provides ui) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    when (val screen = stack.last()) {
                        Screen.Library -> LibraryScreen(library, loading, playlists, ps.currentSongId)
                        Screen.Search -> SearchScreen(library, ps.currentSongId, onBack = { stack = stack.dropLast(1) })
                        Screen.Settings -> SettingsScreen(libraryVm, onBack = { stack = stack.dropLast(1) })
                        is Screen.Songs -> {
                            val songs = remember(screen, library, playlists, stats, favourites) {
                                screen.source.resolve(library, playlists, stats, favourites)
                            }
                            SongsScreen(screen, songs, library, playlists, ps.currentSongId,
                                onBack = { stack = stack.dropLast(1) })
                        }
                    }
                }
                if (ps.hasMedia) MiniPlayer(ps, library, onOpen = { openPlayer.value = true })
            }

            AnimatedVisibility(
                visible = openPlayer.value && ps.hasMedia,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                NowPlayingScreen(ps, library, onClose = { openPlayer.value = false })
            }
        }

        addToPlaylist?.let { songs ->
            AddToPlaylistDialog(playlists, onDismiss = { addToPlaylist = null }) { playlistId ->
                val n = PlaylistStore.add(playlistId, songs.map { it.id })
                Toast.makeText(context, context.resources.getQuantityString(R.plurals.tracks_added_to_playlist, n, n),
                    Toast.LENGTH_SHORT).show()
                addToPlaylist = null
            }
        }
        details?.let { DetailsDialog(it) { details = null } }
        importReport?.let { ImportReportDialog(it) { importReport = null } }
        editing?.let { song ->
            val albumCount = library.albums.firstOrNull { it.id == song.albumId }?.songs?.size ?: 1
            EditTrackDialog(song, albumCount, onDismiss = { editing = null }) { edits, cover, wholeAlbum ->
                editing = null
                requestEdit(song, edits, cover, wholeAlbum)
            }
        }
        when (dialog) {
            GlobalDialog.SoundAlive -> SoundAliveDialog { dialog = null }
            GlobalDialog.PlaySpeed -> PlaySpeedDialog(ps.speed, playerVm::setSpeed) { dialog = null }
            GlobalDialog.SleepTimer -> SleepTimerDialog(playerVm::setSleepTimer) { dialog = null }
            null -> Unit
        }
    }
}

/** "Name", or "Name 2", "Name 3"... if a playlist with that name already exists. */
private fun uniquePlaylistName(name: String): String {
    val taken = PlaylistStore.playlists.value.map { it.name.lowercase() }.toSet()
    if (name.lowercase() !in taken) return name
    return generateSequence(2) { it + 1 }.map { "$name $it" }.first { it.lowercase() !in taken }
}

// ---------- Background playback helper ----------

object BackgroundPlayback {
    private const val PREFS = "app"
    fun isExempt(context: Context) =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    fun wasPrompted(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("battery_prompted", false)
    fun markPrompted(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("battery_prompted", true).apply()

    @SuppressLint("BatteryLife") // sideloaded music player: keeping playback alive is the core feature
    fun requestExemption(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(direct) }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }
}
