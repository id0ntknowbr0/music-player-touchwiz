package dev.s4music.player.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * "Set as" ringtone / notification / alarm. With a start point or length, a clip is cut without
 * re-encoding (MP3 frames are copied; AAC is remuxed) and saved under Ringtones/, Notifications/
 * or Alarms/. Other formats are used whole.
 */
object RingtoneMaker {
    enum class Kind(val ringtoneType: Int, val directory: String, val flagColumn: String) {
        RINGTONE(RingtoneManager.TYPE_RINGTONE, Environment.DIRECTORY_RINGTONES, MediaStore.Audio.Media.IS_RINGTONE),
        NOTIFICATION(RingtoneManager.TYPE_NOTIFICATION, Environment.DIRECTORY_NOTIFICATIONS, MediaStore.Audio.Media.IS_NOTIFICATION),
        ALARM(RingtoneManager.TYPE_ALARM, Environment.DIRECTORY_ALARMS, MediaStore.Audio.Media.IS_ALARM),
    }

    private enum class Format(val ext: String, val mime: String) { MP3("mp3", "audio/mpeg"), AAC("m4a", "audio/mp4") }

    private fun formatOf(song: Song): Format? = when (song.displayName.substringAfterLast('.', "").lowercase()) {
        "mp3" -> Format.MP3
        "m4a", "aac", "mp4", "3gp" -> Format.AAC
        else -> null
    }

    fun canTrim(song: Song) = song.path.isNotEmpty() && formatOf(song) != null

    fun canWriteSettings(context: Context) = Settings.System.canWrite(context)

    /** Makes the clip (if needed) and sets it as the default sound. Returns false if it couldn't be cut. */
    suspend fun apply(context: Context, song: Song, kind: Kind, startMs: Long, endMs: Long?): Boolean =
        withContext(Dispatchers.IO) {
            val whole = startMs <= 0 && (endMs == null || endMs >= song.durationMs)
            val uri = if (whole || !canTrim(song)) song.uri else makeClip(context, song, kind, startMs, endMs ?: song.durationMs)
                ?: return@withContext false
            RingtoneManager.setActualDefaultRingtoneUri(context, kind.ringtoneType, uri)
            true
        }

    private fun makeClip(context: Context, song: Song, kind: Kind, startMs: Long, endMs: Long): Uri? {
        val format = formatOf(song) ?: return null
        val resolver = context.contentResolver
        val safeTitle = song.title.map { if (it in "/\\:*?\"<>|") '_' else it }.joinToString("").take(60)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$safeTitle.${format.ext}")
            put(MediaStore.Audio.Media.MIME_TYPE, format.mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, kind.directory + "/")
            put(MediaStore.Audio.Media.TITLE, song.title)
            put(MediaStore.Audio.Media.ARTIST, song.artist)
            put(MediaStore.Audio.Media.IS_MUSIC, 0)
            put(kind.flagColumn, 1)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val ok = runCatching {
            when (format) {
                Format.MP3 -> {
                    val clip = cutMp3(File(song.path).readBytes(), startMs, endMs)
                    if (clip.isEmpty()) error("no MP3 frames in range")
                    resolver.openOutputStream(uri, "wt")!!.use { it.write(clip) }
                }
                Format.AAC -> resolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
                    remuxAac(song.path, pfd.fileDescriptor, startMs * 1000, endMs * 1000)
                }
            }
        }.isSuccess
        if (!ok) {
            resolver.delete(uri, null, null)
            return null
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    // ---- MP3: copy whole frames between two times ----

    private val BITRATES_V1 = arrayOf(
        intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448),   // layer I
        intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384),      // layer II
        intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320),       // layer III
    )
    private val BITRATES_V2 = arrayOf(
        intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256),      // layer I
        intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160),           // layers II & III
    )
    private val SAMPLE_RATES = mapOf(3 to intArrayOf(44100, 48000, 32000), 2 to intArrayOf(22050, 24000, 16000),
        0 to intArrayOf(11025, 12000, 8000))

    private class Frame(val length: Int, val durationMs: Double)

    private fun frameAt(d: ByteArray, p: Int): Frame? {
        if (p + 4 > d.size) return null
        val b1 = d[p + 1].toInt() and 0xFF
        val b2 = d[p + 2].toInt() and 0xFF
        if (d[p].toInt() and 0xFF != 0xFF || b1 and 0xE0 != 0xE0) return null
        val version = (b1 shr 3) and 3          // 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5
        val layer = (b1 shr 1) and 3            // 3 = I, 2 = II, 1 = III
        val bitrateIndex = (b2 shr 4) and 0xF
        val rateIndex = (b2 shr 2) and 3
        if (version == 1 || layer == 0 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return null
        val padding = (b2 shr 1) and 1
        val sampleRate = SAMPLE_RATES.getValue(version)[rateIndex]
        val kbps = if (version == 3) BITRATES_V1[3 - layer][bitrateIndex]
        else BITRATES_V2[if (layer == 3) 0 else 1][bitrateIndex]
        val samples = when {
            layer == 3 -> 384
            layer == 2 || version == 3 -> 1152
            else -> 576
        }
        val length = if (layer == 3) (12 * kbps * 1000 / sampleRate + padding) * 4
        else samples / 8 * kbps * 1000 / sampleRate + padding
        return Frame(length, samples * 1000.0 / sampleRate)
    }

    /** Frames from [startMs] to [endMs], without tags or the Xing/Info header (whose counts would be wrong). */
    fun cutMp3(d: ByteArray, startMs: Long, endMs: Long): ByteArray {
        var p = 0
        if (d.size > 10 && d[0] == 'I'.code.toByte() && d[1] == 'D'.code.toByte() && d[2] == '3'.code.toByte()) {
            val size = (d[6].toInt() and 0x7F shl 21) or (d[7].toInt() and 0x7F shl 14) or
                (d[8].toInt() and 0x7F shl 7) or (d[9].toInt() and 0x7F)
            p = 10 + size + if (d[5].toInt() and 0x10 != 0) 10 else 0
        }
        val out = ByteArrayOutputStream()
        var timeMs = 0.0
        var first = true
        while (p < d.size && timeMs < endMs) {
            val f = frameAt(d, p)
            // A real frame is followed by another frame header (or the end of the file).
            if (f == null || f.length <= 4 || (p + f.length < d.size - 4 && frameAt(d, p + f.length) == null)) {
                p++
                continue
            }
            val isInfoFrame = first && String(d, p, minOf(f.length, d.size - p), Charsets.ISO_8859_1)
                .let { it.contains("Xing") || it.contains("Info") || it.contains("VBRI") }
            first = false
            if (!isInfoFrame) {
                if (timeMs >= startMs) out.write(d, p, minOf(f.length, d.size - p))
                timeMs += f.durationMs
            }
            p += f.length
        }
        return out.toByteArray()
    }

    // ---- AAC: copy samples into a new MP4 container ----

    private fun remuxAac(path: String, fd: java.io.FileDescriptor, startUs: Long, endUs: Long) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(path)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC
            }
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val out = muxer.addTrack(format)
            muxer.start()
            val buffer = ByteBuffer.allocate(512 * 1024)
            val info = MediaCodec.BufferInfo()
            var firstUs = -1L
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val t = extractor.sampleTime
                if (t > endUs) break
                if (firstUs < 0) firstUs = t
                info.set(0, size, t - firstUs, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                muxer.writeSampleData(out, buffer, info)
                extractor.advance()
            }
            muxer.stop()
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
        }
    }
}
