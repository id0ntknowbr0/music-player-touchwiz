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
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.s4music.player.MainActivity
import dev.s4music.player.R
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
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Replacement for the S4's CorePlayerService. Media3 provides the notification,
 * lock-screen / Bluetooth / headset controls, audio focus and "becoming noisy" handling.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val soundAlive = SoundAliveEngine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val statePrefs by lazy { getSharedPreferences("playback_state", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                soundAlive.attach(audioSessionId, PlaybackBridge.soundAlive.value)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.mediaId?.toLongOrNull()?.let { PlayStats.recordPlay(it) }
                PlaybackBridge.abRepeat.value = null
                saveState()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) saveState()
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                        Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_TIMELINE_CHANGED)
                ) updateWidget()
            }
        })
        soundAlive.attach(player.audioSessionId, PlaybackBridge.soundAlive.value)

        val openPlayer = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(openPlayer)
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_notification)
            }
        )

        // SoundAlive changes from the UI
        scope.launch { PlaybackBridge.soundAlive.collect { soundAlive.apply(it) } }

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

        // Music auto off (sleep timer)
        scope.launch {
            PlaybackBridge.sleepAtElapsed.collectLatest { at ->
                if (at == null) return@collectLatest
                delay((at - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                player.pause()
                PlaybackBridge.sleepAtElapsed.value = null
            }
        }

        restoreQueue()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

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
        scope.cancel()
        soundAlive.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map(::resolveMediaItem).toMutableList())

        /** Lets the Bluetooth play button / system media resumption restart the last queue. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val saved = loadSavedQueue()
                ?: throw UnsupportedOperationException("Nothing to resume")
            saved
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
