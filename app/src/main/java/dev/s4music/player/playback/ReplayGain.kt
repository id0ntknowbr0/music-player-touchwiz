package dev.s4music.player.playback

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.TagTextField
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.jaudiotagger.tag.mp4.field.Mp4TagReverseDnsField
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.pow

/** Reads ReplayGain tags (ID3 TXXX, Vorbis/FLAC comments, MP4 freeform atoms) and caches them per song. */
object ReplayGain {
    data class Gains(val trackDb: Float?, val albumDb: Float?) {
        fun pick(mode: SmartVolume): Float? = when (mode) {
            SmartVolume.OFF -> null
            SmartVolume.TRACK -> trackDb ?: albumDb
            SmartVolume.ALBUM -> albumDb ?: trackDb
        }
    }

    private val NONE = Gains(null, null)
    private val cache = LruCache<Long, Gains>(512)

    init {
        TagOptionSingleton.getInstance().isAndroid = true
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    fun cached(songId: Long): Gains? = cache.get(songId)

    suspend fun load(context: Context, songId: Long): Gains = withContext(Dispatchers.IO) {
        cache.get(songId) ?: (pathOf(context, songId)?.let(::readFile) ?: NONE).also { cache.put(songId, it) }
    }

    fun forget(songId: Long) { cache.remove(songId) }

    private fun pathOf(context: Context, songId: Long): String? = runCatching {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)
        @Suppress("DEPRECATION")
        context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    fun readFile(path: String): Gains = runCatching {
        val tag = AudioFileIO.read(File(path)).tag ?: return NONE
        fromTag(tag)
    }.getOrDefault(NONE)

    internal fun fromTag(tag: Tag): Gains {
        var track: Float? = null
        var album: Float? = null
        fun consider(key: String?, value: String?) {
            when (key?.trim()?.lowercase()) {
                "replaygain_track_gain" -> track = track ?: parseDb(value)
                "replaygain_album_gain" -> album = album ?: parseDb(value)
            }
        }
        val fields = tag.fields
        while (fields.hasNext()) {
            when (val f = fields.next()) {
                is AbstractID3v2Frame -> (f.body as? FrameBodyTXXX)?.let { consider(it.description, it.text) }
                is Mp4TagReverseDnsField -> consider(f.descriptor, f.content)
                is TagTextField -> consider(f.id, f.content)
            }
        }
        // ID3 tags can hold several TXXX frames under one key; make sure none were missed.
        if (track == null || album == null) {
            runCatching { tag.getFields("TXXX") }.getOrNull()?.forEach { f ->
                ((f as? AbstractID3v2Frame)?.body as? FrameBodyTXXX)?.let { consider(it.description, it.text) }
            }
        }
        return Gains(track, album)
    }

    private val NUMBER = Regex("""[-+]?\d+(?:[.,]\d+)?""")

    /** "-7.03 dB" → -7.03 */
    fun parseDb(value: String?): Float? =
        value?.let { NUMBER.find(it)?.value?.replace(',', '.')?.toFloatOrNull() }?.takeIf { it in -30f..30f }

    fun dbToVolume(db: Float): Float = 10f.pow(db / 20f)
}
