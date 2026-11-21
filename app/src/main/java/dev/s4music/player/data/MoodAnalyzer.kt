package dev.s4music.player.data

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max

/**
 * Measures tempo, level and brightness from ~20 s in the middle of a song, entirely on the
 * device. The numbers are rough by design: they only have to sort songs into a 5×5 grid.
 */
object MoodAnalyzer {
    data class Result(val tempo: Float?, val rmsDb: Float, val brightness: Float)

    private const val WINDOW_US = 20_000_000L
    private const val HOP = 512          // samples per envelope step (~11.6 ms at 44.1 kHz)
    private const val TIMEOUT_US = 10_000L

    fun analyze(path: String): Result? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            extractor.selectTrack(track)
            val startUs = if (durationUs > WINDOW_US * 2) (durationUs * 0.35).toLong() else 0L
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            codec = MediaCodec.createDecoderByType(mime).apply { configure(format, null, null, 0); start() }
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false

            val maxSamples = (sampleRate.toLong() * WINDOW_US / 1_000_000L).toInt()
            val mono = FloatArray(maxSamples + sampleRate)   // room for one overshooting buffer
            var count = 0
            var inputDone = false
            val info = MediaCodec.BufferInfo()
            var idle = 0

            while (count < maxSamples && idle < 200) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIndex >= 0 -> {
                        idle = 0
                        val out = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                        out.position(info.offset); out.limit(info.offset + info.size)
                        if (floatPcm) {
                            val fb = out.asFloatBuffer()
                            while (fb.remaining() >= channels && count < mono.size) {
                                var sum = 0f
                                repeat(channels) { sum += fb.get() }
                                mono[count++] = sum / channels
                            }
                        } else {
                            val sb = out.asShortBuffer()
                            while (sb.remaining() >= channels && count < mono.size) {
                                var sum = 0f
                                repeat(channels) { sum += sb.get() / 32768f }
                                mono[count++] = sum / channels
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    else -> idle++
                }
            }
            if (count < sampleRate * 3) return null   // under 3 s of audio: not enough to judge
            return measure(mono, count, sampleRate)
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /** Pure maths on decoded mono PCM, separate so it can be unit-tested. */
    fun measure(x: FloatArray, n: Int, sampleRate: Int): Result {
        var sumSq = 0.0
        var crossings = 0
        for (i in 0 until n) {
            sumSq += x[i] * x[i]
            if (i > 0 && (x[i] >= 0f) != (x[i - 1] >= 0f)) crossings++
        }
        val rmsDb = (10 * log10(max(sumSq / n, 1e-10))).toFloat()
        val crossingsPerSec = crossings.toFloat() * sampleRate / n
        val brightness = ((crossingsPerSec - 800f) / 3000f).coerceIn(0f, 1f)
        return Result(estimateTempo(x, n, sampleRate), rmsDb, brightness)
    }

    /** Autocorrelation of the onset envelope (rises in loudness), searched between 60 and 180 BPM. */
    fun estimateTempo(x: FloatArray, n: Int, sampleRate: Int): Float? {
        val frames = n / HOP
        if (frames < 64) return null
        val logEnergy = FloatArray(frames) { f ->
            var e = 0f
            for (i in f * HOP until (f + 1) * HOP) e += x[i] * x[i]
            ln(e + 1e-6f)
        }
        val onset = FloatArray(frames) { f -> if (f == 0) 0f else max(0f, logEnergy[f] - logEnergy[f - 1]) }
        val mean = onset.average().toFloat()
        for (i in onset.indices) onset[i] -= mean

        val hopSec = HOP.toFloat() / sampleRate
        val minLag = (60f / 180f / hopSec).toInt().coerceAtLeast(1)
        val maxLag = (60f / 60f / hopSec).toInt().coerceAtMost(frames / 2)
        var bestLag = -1
        var best = 0f
        var energy0 = 0f
        for (i in 0 until frames) energy0 += onset[i] * onset[i]
        if (energy0 <= 0f) return null
        for (lag in minLag..maxLag) {
            var r = 0f
            for (i in 0 until frames - lag) r += onset[i] * onset[i + lag]
            // Gentle preference for tempos near 120 BPM, which resolves double/half-tempo ties.
            val bpm = 60f / (lag * hopSec)
            val weight = exp(-0.5f * (ln(bpm / 120f) / 0.7f).let { it * it })
            val score = r / energy0 * weight
            if (score > best) { best = score; bestLag = lag }
        }
        if (bestLag < 0 || best < 0.02f) return null
        return 60f / (bestLag * hopSec)
    }
}
