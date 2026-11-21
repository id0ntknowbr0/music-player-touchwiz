package dev.s4music.player.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Size
import android.widget.RemoteViews
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.s4music.player.MainActivity
import dev.s4music.player.R
import dev.s4music.player.data.CoverStore
import dev.s4music.player.playback.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the widget shows; persisted so the widget can redraw while the service isn't running. */
data class WidgetState(
    val title: String = "",
    val artist: String = "",
    val albumId: Long? = null,
    val songId: Long? = null,
    val playing: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val album: String = "",
)

object WidgetUpdater {
    private const val PREFS = "widget_state"
    private var lastArtKey: String? = null
    private var lastArt: Bitmap? = null

    fun save(context: Context, s: WidgetState) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("title", s.title).putString("artist", s.artist)
            .putLong("album", s.albumId ?: -1L).putLong("song", s.songId ?: -1L).putBoolean("playing", s.playing)
            .putBoolean("shuffle", s.shuffle).putInt("repeat", s.repeatMode)
            .putString("album_title", s.album)
            .apply()
    }

    fun load(context: Context): WidgetState {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return WidgetState(
            title = p.getString("title", "").orEmpty(),
            artist = p.getString("artist", "").orEmpty(),
            albumId = p.getLong("album", -1L).takeIf { it >= 0 },
            songId = p.getLong("song", -1L).takeIf { it >= 0 },
            playing = false, // after a restart nothing is playing
            shuffle = p.getBoolean("shuffle", false),
            repeatMode = p.getInt("repeat", Player.REPEAT_MODE_OFF),
            album = p.getString("album_title", "").orEmpty(),
        )
    }

    /** The widget sizes and their layouts. */
    private val WIDGETS = listOf(
        MusicWidgetProvider::class.java to R.layout.widget_player,              // 4x1
        MusicWidgetLargeProvider::class.java to R.layout.widget_player_large,   // 4x2
        MusicWidgetSmallProvider::class.java to R.layout.widget_play_pause,     // 1x1
    )

    /** Redraws every placed widget. Safe to call often; album art is cached. */
    suspend fun update(context: Context, s: WidgetState) {
        val manager = AppWidgetManager.getInstance(context)
        save(context, s)
        var art: Bitmap? = null
        var artLoaded = false
        for ((provider, layout) in WIDGETS) {
            val ids = manager.getAppWidgetIds(ComponentName(context, provider))
            if (ids.isEmpty()) continue
            if (layout != R.layout.widget_play_pause && !artLoaded) {
                art = albumArt(context, s.songId, s.albumId)
                artLoaded = true
            }
            manager.updateAppWidget(ids, build(context, s, art, layout))
        }
    }

    private suspend fun albumArt(context: Context, songId: Long?, albumId: Long?): Bitmap? {
        val key = "$songId/$albumId#${CoverStore.version.value}"
        if (key == lastArtKey && lastArt != null) return lastArt
        val bmp = withContext(Dispatchers.IO) {
            CoverStore.find(context, songId, albumId)?.let { CoverStore.decode(it, ART_PX) }
                ?: albumId?.let {
                    runCatching {
                        context.contentResolver.loadThumbnail(
                            ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, it),
                            Size(ART_PX, ART_PX), null)
                    }.getOrNull()
                }
        }
        lastArtKey = key
        lastArt = bmp
        return bmp
    }

    /** Album art size: enough for the 4x2 widget's large cover. */
    private const val ART_PX = 400

    fun build(context: Context, s: WidgetState, art: Bitmap?, layout: Int = R.layout.widget_player): RemoteViews {
        if (layout == R.layout.widget_play_pause) {
            return RemoteViews(context.packageName, layout).apply {
                setImageViewResource(R.id.widget_play, if (s.playing) R.drawable.w_btn_pause else R.drawable.w_btn_play)
                setContentDescription(R.id.widget_play, context.getString(if (s.playing) R.string.pause else R.string.play))
                setOnClickPendingIntent(R.id.widget_play, action(context, WidgetActionReceiver.PLAY_PAUSE, 1))
            }
        }
        return RemoteViews(context.packageName, layout).apply {
            if (layout == R.layout.widget_player_large) setTextViewText(R.id.widget_album, s.album)
            setTextViewText(R.id.widget_title, s.title.ifEmpty { context.getString(R.string.app_name) })
            setTextViewText(R.id.widget_artist, s.artist.ifEmpty { context.getString(R.string.widget_tap_to_resume) })
            if (art != null) setImageViewBitmap(R.id.widget_art, art)
            else setImageViewResource(R.id.widget_art, R.drawable.tw_default_art_large)
            setImageViewResource(R.id.widget_play, if (s.playing) R.drawable.w_btn_pause else R.drawable.w_btn_play)
            setContentDescription(R.id.widget_play, context.getString(if (s.playing) R.string.pause else R.string.play))
            setImageViewResource(R.id.widget_shuffle, if (s.shuffle) R.drawable.tw_w_shuffle_on else R.drawable.tw_w_shuffle_off)
            setImageViewResource(R.id.widget_repeat, when (s.repeatMode) {
                Player.REPEAT_MODE_ALL -> R.drawable.tw_w_repeat_all
                Player.REPEAT_MODE_ONE -> R.drawable.tw_w_repeat_one
                else -> R.drawable.tw_w_repeat_off
            })

            val open = PendingIntent.getActivity(context, 0,
                Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            setOnClickPendingIntent(R.id.widget_art_frame, open)
            setOnClickPendingIntent(R.id.widget_title, open)
            setOnClickPendingIntent(R.id.widget_artist, open)
            setOnClickPendingIntent(R.id.widget_play, action(context, WidgetActionReceiver.PLAY_PAUSE, 1))
            setOnClickPendingIntent(R.id.widget_prev, action(context, WidgetActionReceiver.PREV, 2))
            setOnClickPendingIntent(R.id.widget_next, action(context, WidgetActionReceiver.NEXT, 3))
            setOnClickPendingIntent(R.id.widget_shuffle, action(context, WidgetActionReceiver.SHUFFLE, 4))
            setOnClickPendingIntent(R.id.widget_repeat, action(context, WidgetActionReceiver.REPEAT, 5))
        }
    }

    private fun action(context: Context, action: String, code: Int) = PendingIntent.getBroadcast(
        context, code,
        Intent(context, WidgetActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** 4x1 widget. The 4x2 and 1x1 widgets share its update logic. */
open class MusicWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                WidgetUpdater.update(context.applicationContext, WidgetUpdater.load(context))
            } finally {
                pending.finish()
            }
        }
    }
}

/** 4x2 widget with large album art. */
class MusicWidgetLargeProvider : MusicWidgetProvider()

/** 1x1 play / pause widget. */
class MusicWidgetSmallProvider : MusicWidgetProvider()

/**
 * Handles widget button taps by connecting a MediaController to [PlaybackService].
 * Connecting starts the service if needed (it restores the last queue), so the
 * widget can start playback even when the app has been closed.
 */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            var controller: MediaController? = null
            try {
                val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
                controller = withTimeoutOrNull(5_000) { MediaController.Builder(app, token).buildAsync().await() }
                    ?: return@launch
                val c = controller
                // Give the service a moment to restore the saved queue after a cold start.
                withTimeoutOrNull(3_000) { while (c.mediaItemCount == 0) delay(100) }
                when (action) {
                    PLAY_PAUSE -> if (c.isPlaying) c.pause() else {
                        if (c.playbackState == Player.STATE_IDLE) c.prepare()
                        if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0)
                        c.play()
                    }
                    NEXT -> c.seekToNext()
                    PREV -> c.seekToPrevious()
                    SHUFFLE -> c.shuffleModeEnabled = !c.shuffleModeEnabled
                    REPEAT -> c.repeatMode = when (c.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                }
                delay(300) // let the command reach the session before disconnecting
            } finally {
                controller?.release()
                pending.finish()
            }
        }
    }

    companion object {
        const val PLAY_PAUSE = "dev.s4music.player.widget.PLAY_PAUSE"
        const val NEXT = "dev.s4music.player.widget.NEXT"
        const val PREV = "dev.s4music.player.widget.PREV"
        const val SHUFFLE = "dev.s4music.player.widget.SHUFFLE"
        const val REPEAT = "dev.s4music.player.widget.REPEAT"
    }
}
