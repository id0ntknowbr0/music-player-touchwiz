package dev.s4music.player.playback

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dev.s4music.player.data.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process state shared between the UI and [PlaybackService] for the S4-specific
 * features that MediaSession has no standard command for.
 */
object PlaybackBridge {
    /** A-B repeat: [a] set first, loop becomes active once [b] is set. */
    data class AbRepeat(val a: Long, val b: Long? = null)

    val abRepeat = MutableStateFlow<AbRepeat?>(null)

    /** "Music auto off": [android.os.SystemClock.elapsedRealtime] at which playback pauses. */
    val sleepAtElapsed = MutableStateFlow<Long?>(null)

    /** "Music auto off" → "After current song": pause when the playing song ends. */
    val sleepAfterCurrent = MutableStateFlow(false)

    /** Whether SoundAlive got the 10-band EQ with preamp and balance on this device (null until known). */
    val eqAdvanced = MutableStateFlow<Boolean?>(null)

    private val _soundAlive = MutableStateFlow(SoundAliveSettings())
    val soundAlive: StateFlow<SoundAliveSettings> = _soundAlive.asStateFlow()
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("sound_alive", Context.MODE_PRIVATE)
        _soundAlive.value = SoundAliveSettings.load(prefs)
    }

    fun setSoundAlive(settings: SoundAliveSettings) {
        _soundAlive.value = settings
        settings.save(prefs)
    }
}

private const val URI_PREFIX = "uri:"
const val EXTRA_ALBUM_ID = "album_id"

fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id.toString())
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setExtras(android.os.Bundle().apply { putLong(EXTRA_ALBUM_ID, albumId) })
            .build()
    )
    .build()

/** A file opened from another app (file manager "Open with"). */
fun externalMediaItem(uri: Uri): MediaItem = MediaItem.Builder()
    .setMediaId(URI_PREFIX + uri)
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(uri.lastPathSegment?.substringAfterLast('/') ?: "Audio")
            .setIsPlayable(true)
            .build()
    )
    .build()

/** Controllers may strip the playback URI; rebuild it from the media ID. */
fun resolveMediaItem(item: MediaItem): MediaItem {
    if (item.localConfiguration != null) return item
    val id = item.mediaId
    val uri = when {
        id.startsWith(URI_PREFIX) -> Uri.parse(id.removePrefix(URI_PREFIX))
        else -> id.toLongOrNull()?.let {
            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it)
        }
    } ?: return item
    return item.buildUpon().setUri(uri).build()
}
