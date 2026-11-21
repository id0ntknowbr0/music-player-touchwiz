package dev.s4music.player.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.s4music.player.MainActivity
import dev.s4music.player.R
import dev.s4music.player.data.AppSettings
import dev.s4music.player.data.MediaStoreRepository
import dev.s4music.player.data.PlayStats
import dev.s4music.player.widget.WidgetState
import dev.s4music.player.widget.WidgetUpdater
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Replacement for the S4's CorePlayerService. Media3 provides the notification,
 * lock-screen / Bluetooth / headset controls, audio focus and "becoming noisy" handling.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private val browseTree by lazy { BrowseTree(this) }
    private lateinit var player: ExoPlayer
    private val soundAlive = SoundAliveEngine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val statePrefs by lazy { getSharedPreferences("playback_state", MODE_PRIVATE) }
    private lateinit var crossfader: Crossfader

    // The main player's volume is the product of these (see applyVolume).
    private var smartVolume = 1f   // ReplayGain attenuation for the current song
    private var fadeIn = 1f        // crossfade: next song fading in
    private var sleepFade = 1f     // Music auto off: fading out before pausing

    private val musicAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(musicAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                attachEffects(audioSessionId)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.mediaId?.toLongOrNull()?.let { PlayStats.recordPlay(it) }
                PlaybackBridge.abRepeat.value = null
                updateSmartVolume()
                saveState()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) saveState()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // "After current song" fired: switch the option off again.
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                    PlaybackBridge.sleepAfterCurrent.value = false
                }
            }

            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                // A seek by the user ends a crossfade in progress (the crossfade's own skip happens after its checks).
                if (reason == Player.DISCONTINUITY_REASON_SEEK && old.mediaItemIndex == new.mediaItemIndex) crossfader.cancel()
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                        Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_TIMELINE_CHANGED)
                ) updateWidget()
            }
        })
        attachEffects(player.audioSessionId)

        val openPlayer = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, SessionCallback())
            .setSessionActivity(openPlayer)
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_notification)
            }
        )

        // SoundAlive changes from the UI (Smart volume mode included)
        scope.launch {
            PlaybackBridge.soundAlive.collect {
                soundAlive.apply(it)
                updateSmartVolume()
            }
        }

        // Skip silence
        scope.launch {
            AppSettings.settings.map { it.skipSilence }.distinctUntilChanged().collect { player.skipSilenceEnabled = it }
        }

        // "After current song": ExoPlayer pauses by itself at the end of the item.
        scope.launch {
            PlaybackBridge.sleepAfterCurrent.collect { on ->
                player.pauseAtEndOfMediaItems = on
                if (on) PlaybackBridge.sleepAtElapsed.value = null
            }
        }

        // Output changes: SoundAlive per output, and "Resume when connected".
        scope.launch {
            var previous: OutputRoute? = null
            OutputRoutes.current.collect { route ->
                val before = previous
                previous = route
                if (before == null || before.key == route.key) return@collect
                if (AppSettings.current.soundPerOutput) {
                    PlaybackBridge.saveProfile(before.key)
                    PlaybackBridge.loadProfile(route.key)?.let { PlaybackBridge.setSoundAlive(it) }
                }
                val connected = before.kind == OutputKind.SPEAKER && route.kind != OutputKind.SPEAKER
                if (connected && AppSettings.current.autoResume && !player.playWhenReady && player.mediaItemCount > 0) {
                    // Bluetooth reports the device a moment before audio is routed to it.
                    if (route.kind == OutputKind.BLUETOOTH) delay(1_500)
                    if (OutputRoutes.current.value.key == route.key && !player.playWhenReady) {
                        if (player.playbackState == Player.STATE_IDLE) player.prepare()
                        if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
                        player.play()
                    }
                }
            }
        }

        crossfader = Crossfader(
            player = player,
            scope = scope,
            newHelperPlayer = {
                // No audio focus or noisy handling: the main player owns those, and the helper only lives a few seconds.
                ExoPlayer.Builder(this).setAudioAttributes(musicAttributes, false).build()
            },
            fadeMs = { AppSettings.current.crossfadeSec * 1000L },
            allowed = {
                player.repeatMode != Player.REPEAT_MODE_ONE &&
                    PlaybackBridge.abRepeat.value?.b == null &&
                    !PlaybackBridge.sleepAfterCurrent.value
            },
            outgoingVolume = { smartVolume * sleepFade },
            setFadeIn = { fadeIn = it; applyVolume() },
        )
        crossfader.start()

        // A-B repeat loop
        scope.launch {
            PlaybackBridge.abRepeat.collectLatest { ab ->
                val b = ab?.b ?: return@collectLatest
                while (isActive) {
                    if (player.currentPosition >= b) player.seekTo(ab.a)
                    delay(40)
                }
            }
        }

        // Music auto off (sleep timer): fade out over the last few seconds, then pause.
        scope.launch {
            PlaybackBridge.sleepAtElapsed.collectLatest { at ->
                sleepFade = 1f
                applyVolume()
                if (at == null) return@collectLatest
                PlaybackBridge.sleepAfterCurrent.value = false
                delay((at - SLEEP_FADE_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                try {
                    while (true) {
                        val left = at - SystemClock.elapsedRealtime()
                        if (left <= 0) break
                        val t = (left.toFloat() / SLEEP_FADE_MS).coerceIn(0f, 1f)
                        sleepFade = t * t   // eases out, sounds more even than a straight line
                        applyVolume()
                        delay(100)
                    }
                    player.pause()
                    PlaybackBridge.sleepAtElapsed.value = null
                } finally {
                    sleepFade = 1f
                    applyVolume()
                }
            }
        }

        restoreQueue()
    }

    private companion object {
        /** Music auto off fades the volume out over this long before pausing. */
        const val SLEEP_FADE_MS = 10_000L
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0 || p.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        saveState()
        // Final widget refresh showing "paused", on a scope that outlives this service.
        val last = widgetState().copy(playing = false)
        val app = applicationContext
        CoroutineScope(Dispatchers.Main).launch { WidgetUpdater.update(app, last) }
        crossfader.release()
        scope.cancel()
        soundAlive.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** Playback commands plus the browse tree for Android Auto and other media browsers. */
    private inner class SessionCallback : MediaLibrarySession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            // "Play <query>" from a voice assistant arrives as an item with only a search query.
            browseTree.expand(mediaItems)?.takeIf { mediaItems.single().requestMetadata.searchQuery != null }
                ?.first?.toMutableList()
                ?: mediaItems.map { browseTree.resolve(it) }.toMutableList()
        }

        /** A song picked from a list in Android Auto plays the whole list from that song. */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val expanded = if (mediaItems.size == 1 && mediaItems[0].localConfiguration == null) {
                browseTree.expand(mediaItems)
            } else null
            if (expanded != null) {
                MediaSession.MediaItemsWithStartPosition(expanded.first, expanded.second, C.TIME_UNSET)
            } else {
                MediaSession.MediaItemsWithStartPosition(mediaItems.map { browseTree.resolve(it) }, startIndex, startPositionMs)
            }
        }

        /** Lets the Bluetooth play button / system media resumption restart the last queue. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val saved = loadSavedQueue()
                ?: throw UnsupportedOperationException("Nothing to resume")
            saved
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(browseTree.root(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val children = browseTree.children(parentId)
                ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            LibraryResult.ofItemList(children.page(page, pageSize), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            browseTree.item(mediaId)?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            scope.launch { session.notifySearchResultChanged(browser, query, browseTree.search(query).size, params) }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            LibraryResult.ofItemList(browseTree.searchItems(query).page(page, pageSize), params)
        }
    }

    private fun <T> List<T>.page(page: Int, pageSize: Int): List<T> =
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) this else drop(page * pageSize).take(pageSize)

    // ---- Volume: Smart volume x crossfade x sleep fade ----

    private fun attachEffects(audioSessionId: Int) {
        soundAlive.attach(audioSessionId, PlaybackBridge.soundAlive.value)
        PlaybackBridge.eqAdvanced.value = soundAlive.advanced
    }

    private fun applyVolume() {
        player.volume = (smartVolume * fadeIn * sleepFade).coerceIn(0f, 1f)
    }

    private fun setSmartGainDb(db: Float) {
        // Cuts are applied as player volume; boosts go through LoudnessEnhancer, which limits instead of clipping.
        smartVolume = if (db < 0f) ReplayGain.dbToVolume(db) else 1f
        soundAlive.setBoost(if (db > 0f) (db * 100).toInt() else 0)
        applyVolume()
    }

    private fun updateSmartVolume() {
        val mode = PlaybackBridge.soundAlive.value.smartVolume
        val id = player.currentMediaItem?.mediaId?.toLongOrNull()
        if (mode == SmartVolume.OFF || id == null) {
            setSmartGainDb(0f)
            return
        }
        val cached = ReplayGain.cached(id)
        if (cached != null) setSmartGainDb(cached.pick(mode) ?: 0f)
        else scope.launch {
            val gains = ReplayGain.load(this@PlaybackService, id)
            if (player.currentMediaItem?.mediaId == id.toString()) setSmartGainDb(gains.pick(mode) ?: 0f)
        }
        // Read the next song's tags now so its gain is ready the moment it starts.
        val next = player.nextMediaItemIndex
        if (next != C.INDEX_UNSET) player.getMediaItemAt(next).mediaId.toLongOrNull()?.let { nextId ->
            if (ReplayGain.cached(nextId) == null) scope.launch { ReplayGain.load(this@PlaybackService, nextId) }
        }
    }

    // ---- Home screen widget ----

    private var widgetJob: Job? = null

    private fun widgetState(): WidgetState {
        val md = player.mediaMetadata
        val item = player.currentMediaItem
        return WidgetState(
            title = (item?.mediaMetadata?.title ?: md.title)?.toString().orEmpty(),
            artist = (item?.mediaMetadata?.artist ?: md.artist)?.toString().orEmpty(),
            albumId = item?.mediaMetadata?.extras?.getLong(EXTRA_ALBUM_ID, -1L)?.takeIf { it >= 0 },
            songId = item?.mediaId?.toLongOrNull(),
            playing = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING),
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            album = (item?.mediaMetadata?.albumTitle ?: md.albumTitle)?.toString().orEmpty(),
        )
    }

    private fun updateWidget() {
        widgetJob?.cancel()
        val state = widgetState()
        widgetJob = scope.launch {
            delay(150) // coalesce bursts of events
            WidgetUpdater.update(this@PlaybackService, state)
        }
    }

    // ---- Queue persistence (the S4 also resumed where you left off) ----

    private fun saveState() {
        if (!::player.isInitialized) return
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        if (ids.isEmpty() || ids.any { it.toLongOrNull() == null }) return
        statePrefs.edit()
            .putString("queue", ids.joinToString(","))
            .putInt("index", player.currentMediaItemIndex)
            .putLong("position", player.currentPosition)
            .putInt("repeat", player.repeatMode)
            .putBoolean("shuffle", player.shuffleModeEnabled)
            .apply()
    }

    private suspend fun loadSavedQueue(): MediaSession.MediaItemsWithStartPosition? {
        val ids = statePrefs.getString("queue", null)?.split(',')?.mapNotNull { it.toLongOrNull() }
        if (ids.isNullOrEmpty()) return null
        val library = runCatching { MediaStoreRepository.load(this) }.getOrNull() ?: return null
        val savedIndex = statePrefs.getInt("index", 0)
        val currentId = ids.getOrNull(savedIndex)
        val items = ids.mapNotNull { library.byId[it]?.toMediaItem() }
        if (items.isEmpty()) return null
        val index = items.indexOfFirst { it.mediaId == currentId.toString() }
        return MediaSession.MediaItemsWithStartPosition(
            items,
            index.coerceAtLeast(0),
            if (index >= 0) statePrefs.getLong("position", 0) else 0,
        )
    }

    private fun restoreQueue() {
        scope.launch {
            val saved = loadSavedQueue() ?: return@launch
            if (player.mediaItemCount > 0) return@launch   // a controller already started something
            player.repeatMode = statePrefs.getInt("repeat", Player.REPEAT_MODE_OFF)
            player.shuffleModeEnabled = statePrefs.getBoolean("shuffle", false)
            player.setMediaItems(saved.mediaItems, saved.startIndex, saved.startPositionMs)
            player.prepare()
        }
    }
}
