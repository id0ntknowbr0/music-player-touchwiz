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
import dev.s4music.player.data.CoverStore
import dev.s4music.player.data.FileOps
import dev.s4music.player.data.Library
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
}

sealed interface Screen {
    data object Library : Screen
    data object Search : Screen
    data class Songs(val title: String, val source: SongSource) : Screen
}

private const val SMART_LIMIT = 50

fun SongSource.resolve(lib: Library, playlists: List<Playlist>, stats: Map<Long, PlayStats.Stat>): List<Song> =
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
    }

enum class GlobalDialog { SoundAlive, PlaySpeed, SleepTimer }

/** Actions any screen can trigger; provided through [LocalUi]. */
class UiActions(
    val player: PlayerViewModel,
    val navigate: (Screen) -> Unit,
    val addToPlaylist: (List<Song>) -> Unit,
    val showDetails: (Song) -> Unit,
    val showDialog: (GlobalDialog) -> Unit,
    val closePlayer: () -> Unit,
    val editSong: (Song) -> Unit,
    val deleteSongs: (List<Song>) -> Unit,
    val changeAlbumCover: (List<Song>) -> Unit,
) {
    fun songMenu(song: Song, extra: List<MenuAction> = emptyList()) = listOf(
        MenuAction("Play next") { player.playNext(listOf(song)) },
        MenuAction("Add to queue") { player.addToQueue(listOf(song)) },
        MenuAction("Add to playlist") { addToPlaylist(listOf(song)) },
        MenuAction("Edit track info") { editSong(song) },
        MenuAction("Details") { showDetails(song) },
    ) + extra + MenuAction("Delete") { deleteSongs(listOf(song)) }

    fun groupMenu(songs: () -> List<Song>, extra: List<MenuAction> = emptyList(), allowDelete: Boolean = true) = listOf(
        MenuAction("Play") { player.playSongs(songs()) },
        MenuAction("Shuffle") { player.playSongs(songs(), shuffle = true) },
        MenuAction("Play next") { player.playNext(songs()) },
        MenuAction("Add to queue") { player.addToQueue(songs()) },
        MenuAction("Add to playlist") { addToPlaylist(songs()) },
    ) + extra + if (allowDelete) listOf(MenuAction("Delete tracks") { deleteSongs(songs()) }) else emptyList()
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
            libraryVm.reload(debounceMs = 300)
            Toast.makeText(context, if (songs.size == 1) "Deleted" else "${songs.size} tracks deleted", Toast.LENGTH_SHORT).show()
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
                    val what = if (edits == null) "Cover changed" else "Track info saved"
                    Toast.makeText(context, if (failed > 0) "$what ($failed files couldn't be changed)" else what,
                        Toast.LENGTH_SHORT).show()
                }.onFailure { e ->
                    if (isCurrent && wasPlaying) playerVm.togglePlay()
                    Toast.makeText(context, "Couldn't save: ${e.message ?: "unsupported file"}", Toast.LENGTH_LONG).show()
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

    val ui = remember(playerVm) {
        UiActions(
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
                        is Screen.Songs -> {
                            val songs = remember(screen, library, playlists, stats) {
                                screen.source.resolve(library, playlists, stats)
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
                Toast.makeText(context,
                    if (n == 1) "1 track added to playlist" else "$n tracks added to playlist",
                    Toast.LENGTH_SHORT).show()
                addToPlaylist = null
            }
        }
        details?.let { DetailsDialog(it) { details = null } }
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
