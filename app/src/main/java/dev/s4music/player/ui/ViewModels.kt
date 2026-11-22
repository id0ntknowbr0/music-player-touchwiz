package dev.s4music.player.ui

import android.app.Application
import android.content.ComponentName
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.s4music.player.data.AppSettings
import dev.s4music.player.data.Library
import dev.s4music.player.data.MediaStoreRepository
import dev.s4music.player.data.Song
import dev.s4music.player.playback.PlaybackBridge
import dev.s4music.player.playback.PlaybackService
import dev.s4music.player.playback.externalMediaItem
import dev.s4music.player.playback.toMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A folder that contains music, before the user's exclusions. */
data class FolderInfo(val path: String, val songCount: Int)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    /** Everything MediaStore returned; null until the first scan finishes. */
    private val raw = MutableStateFlow<List<Song>?>(null)
    val rawSongs: List<Song> get() = raw.value.orEmpty()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** The library as shown: short tracks and excluded folders removed, songs in the chosen order. */
    val library: StateFlow<Library> = combine(
        raw.filterNotNull(),
        AppSettings.settings.map { Triple(it.minDurationSec, it.excludedFolders, it.songSort) }.distinctUntilChanged(),
    ) { songs, _ ->
        val s = AppSettings.current
        MediaStoreRepository.build(MediaStoreRepository.filter(songs, s), s.songSort)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, Library.EMPTY)

    val allFolders: StateFlow<List<FolderInfo>> = raw.filterNotNull().map { songs ->
        songs.groupingBy { it.folder }.eachCount().map { (path, n) -> FolderInfo(path, n) }
            .sortedBy { it.path.lowercase() }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var started = false
    private var reloadJob: Job? = null

    // New, changed or deleted audio files show up without a manual rescan.
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = reload(debounceMs = 1500)
    }

    fun start() {
        if (started) return
        started = true
        getApplication<Application>().contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer,
        )
        reload()
    }

    fun reload(debounceMs: Long = 0) {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            delay(debounceMs)
            raw.value = runCatching { MediaStoreRepository.loadSongs(getApplication()) }.getOrDefault(emptyList())
            _loading.value = false
        }
    }

    /** Tests: show these songs instead of scanning MediaStore. */
    @androidx.annotation.VisibleForTesting
    internal fun showSongs(songs: List<Song>) {
        raw.value = songs
        _loading.value = false
    }

    override fun onCleared() {
        if (started) getApplication<Application>().contentResolver.unregisterContentObserver(observer)
    }
}

data class QueueEntry(val mediaId: String, val title: String, val artist: String)

data class PlayerUiState(
    val connected: Boolean = false,
    val mediaId: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkData: ByteArray? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val speed: Float = 1f,
    val queue: List<QueueEntry> = emptyList(),
    val index: Int = -1,
) {
    val hasMedia get() = queue.isNotEmpty()
    val currentSongId get() = mediaId?.toLongOrNull()
}

class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    private var controller: MediaController? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            sync(rebuildQueue = events.containsAny(
                Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION))
        }
    }

    init {
        viewModelScope.launch {
            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            val c = runCatching { MediaController.Builder(app, token).buildAsync().await() }.getOrNull()
                ?: return@launch
            controller = c
            c.addListener(listener)
            sync(rebuildQueue = true)
            while (isActive) {
                _state.update {
                    it.copy(
                        positionMs = c.currentPosition,
                        durationMs = c.duration.takeIf { d -> d != C.TIME_UNSET } ?: 0L,
                    )
                }
                delay(if (_state.value.isPlaying) 200 else 600)
            }
        }
    }

    private fun sync(rebuildQueue: Boolean) {
        val c = controller ?: return
        val queue = if (rebuildQueue) {
            (0 until c.mediaItemCount).map { i ->
                val item = c.getMediaItemAt(i)
                QueueEntry(item.mediaId, item.mediaMetadata.title?.toString().orEmpty(),
                    item.mediaMetadata.artist?.toString().orEmpty())
            }
        } else _state.value.queue
        val md = c.mediaMetadata
        val itemMd = c.currentMediaItem?.mediaMetadata
        _state.value = PlayerUiState(
            connected = true,
            mediaId = c.currentMediaItem?.mediaId,
            title = (itemMd?.title ?: md.title)?.toString().orEmpty(),
            artist = (itemMd?.artist ?: md.artist)?.toString().orEmpty(),
            album = (itemMd?.albumTitle ?: md.albumTitle)?.toString().orEmpty(),
            artworkData = md.artworkData,
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition,
            durationMs = c.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            speed = c.playbackParameters.speed,
            queue = queue,
            index = c.currentMediaItemIndex,
        )
    }

    // ---- Commands ----

    fun playSongs(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean = false) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        PlaybackBridge.abRepeat.value = null
        if (shuffle) c.shuffleModeEnabled = true
        val start = if (shuffle) songs.indices.random() else startIndex.coerceIn(songs.indices)
        c.setMediaItems(songs.map { it.toMediaItem() }, start, 0L)
        c.prepare()
        c.play()
    }

    fun playNext(songs: List<Song>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return playSongs(songs)
        c.addMediaItems(c.currentMediaItemIndex + 1, songs.map { it.toMediaItem() })
    }

    fun addToQueue(songs: List<Song>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return playSongs(songs)
        c.addMediaItems(songs.map { it.toMediaItem() })
    }

    fun playUri(uri: Uri) {
        val c = controller ?: run {
            // Controller not connected yet; retry shortly.
            viewModelScope.launch { delay(300); playUri(uri) }
            return
        }
        c.setMediaItem(externalMediaItem(uri))
        c.prepare()
        c.play()
    }

    /**
     * Voice search: plays the songs matching every word of [query] (title, artist or album).
     * An empty query ("play music") resumes the queue, or shuffles everything if there is none.
     */
    fun playFromSearch(query: String, library: Library) {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) {
            val c = controller
            if (c != null && c.mediaItemCount > 0) togglePlayIfPaused() else playSongs(library.songs, shuffle = true)
            return
        }
        val found = library.songs.filter { s ->
            val text = "${s.title} ${s.artist} ${s.album}".lowercase()
            words.all { it in text }
        }
        if (found.isNotEmpty()) playSongs(found)
    }

    private fun togglePlayIfPaused() {
        if (controller?.isPlaying == false) togglePlay()
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0)
            c.play()
        }
    }

    /** Like the S4: restarts the track if more than 3 s in, otherwise goes to the previous one. */
    fun previous() { controller?.seekToPrevious() }
    fun next() { controller?.seekToNext() }
    fun seekTo(ms: Long) { controller?.seekTo(ms) }
    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        val dur = c.duration.takeIf { it != C.TIME_UNSET } ?: return
        c.seekTo((c.currentPosition + deltaMs).coerceIn(0, dur))
    }

    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun setSpeed(speed: Float) { controller?.setPlaybackSpeed(speed) }

    fun playQueueIndex(i: Int) {
        val c = controller ?: return
        c.seekToDefaultPosition(i)
        c.play()
    }

    fun removeQueueIndex(i: Int) { controller?.removeMediaItem(i) }

    /** A-B repeat button: set A → set B (loop starts) → clear. */
    fun abRepeatClick() {
        val c = controller ?: return
        val pos = c.currentPosition
        val cur = PlaybackBridge.abRepeat.value
        PlaybackBridge.abRepeat.value = when {
            cur == null -> PlaybackBridge.AbRepeat(pos)
            cur.b == null && pos > cur.a + 1000 -> cur.copy(b = pos).also { c.seekTo(it.a) }
            else -> null
        }
    }

    fun pause() { controller?.pause() }

    /** Drops deleted songs from the play queue. */
    fun removeSongs(ids: Set<Long>) {
        val c = controller ?: return
        for (i in c.mediaItemCount - 1 downTo 0) {
            if (c.getMediaItemAt(i).mediaId.toLongOrNull() in ids) c.removeMediaItem(i)
        }
    }

    /**
     * Refreshes queue entries after their files were edited. The current track is
     * re-opened (its file changed on disk) and resumes from the same position.
     */
    fun updateSongs(updated: List<Song>, resume: Boolean) {
        val c = controller ?: return
        val byId = updated.associateBy { it.id.toString() }
        val current = c.currentMediaItemIndex
        val currentChanged = c.currentMediaItem?.mediaId in byId
        if (currentChanged) {
            val position = c.currentPosition
            val items = (0 until c.mediaItemCount).map { i ->
                val item = c.getMediaItemAt(i)
                byId[item.mediaId]?.toMediaItem() ?: item
            }
            c.setMediaItems(items, current, position)
            c.prepare()
            if (resume) c.play()
        } else {
            for (i in 0 until c.mediaItemCount) {
                byId[c.getMediaItemAt(i).mediaId]?.let { c.replaceMediaItem(i, it.toMediaItem()) }
            }
        }
    }

    /** Sets or cancels "Music auto off" (null = off, which also cancels "after current song"). */
    fun setSleepTimer(minutes: Int?) {
        PlaybackBridge.sleepAfterCurrent.value = false
        PlaybackBridge.sleepAtElapsed.value = minutes?.let { SystemClock.elapsedRealtime() + it * 60_000L }
    }

    fun setSleepAfterCurrent() {
        PlaybackBridge.sleepAtElapsed.value = null
        PlaybackBridge.sleepAfterCurrent.value = true
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        controller?.release()
    }
}
