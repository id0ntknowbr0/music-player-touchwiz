@file:Suppress("DEPRECATION") // Virtualizer: deprecated in API 33 but still functional; no drop-in replacement for an app-level effect.

package dev.s4music.player.playback

import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.util.Log
import androidx.annotation.StringRes
import dev.s4music.player.R
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The 10-band user EQ (Hz). */
val EQ_BANDS_HZ = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

/** The S4's original 7 bands. Presets are tuned on these and interpolated onto [EQ_BANDS_HZ]. */
val S4_BANDS_HZ = intArrayOf(63, 150, 400, 1000, 2500, 6300, 16000)

private fun g(vararg v: Int) = FloatArray(v.size) { v[it].toFloat() }

/** SoundAlive presets from the S4 music player, re-tuned by ear for Android's audio effects. */
enum class SoundPreset(
    @StringRes val label: Int,
    private val s4GainsDb: FloatArray,
    val bassBoost: Int = 0,      // 0..1000
    val virtualizer: Int = 0,    // 0..1000
) {
    NORMAL(R.string.preset_normal, g(0, 0, 0, 0, 0, 0, 0)),
    POP(R.string.preset_pop, g(-1, 1, 3, 4, 3, 1, -1)),
    ROCK(R.string.preset_rock, g(5, 3, -1, -2, 1, 3, 5)),
    DANCE(R.string.preset_dance, g(6, 4, 1, 0, 1, 3, 4), bassBoost = 300),
    JAZZ(R.string.preset_jazz, g(3, 2, 1, 2, -1, 1, 3)),
    CLASSIC(R.string.preset_classic, g(4, 3, 2, 0, 0, 2, 3)),
    VOCAL(R.string.preset_vocal, g(-2, -1, 2, 4, 4, 2, 0)),
    BASS_BOOST(R.string.preset_bass_boost, g(4, 2, 0, 0, 0, 0, 0), bassBoost = 800),
    TREBLE_BOOST(R.string.preset_treble_boost, g(0, 0, 0, 0, 2, 5, 7)),
    VIRTUAL_71(R.string.preset_virtual_71, g(1, 0, 0, 0, 0, 1, 2), virtualizer = 1000),
    CONCERT_HALL(R.string.preset_concert_hall, g(2, 1, 0, 1, 2, 2, 1), virtualizer = 700),
    CAFE(R.string.preset_cafe, g(-1, 0, 2, 2, 1, 0, -2), virtualizer = 300),
    TUBE_AMP(R.string.preset_tube_amp, g(3, 2, 1, 0, -1, -2, -3), bassBoost = 200),
    CUSTOM(R.string.preset_user_eq, g(0, 0, 0, 0, 0, 0, 0));

    /** The preset on the 10-band EQ. */
    val gainsDb: FloatArray by lazy { resample(S4_BANDS_HZ, s4GainsDb, EQ_BANDS_HZ) }
}

/** "Smart volume": evens out loudness between songs using ReplayGain tags. */
enum class SmartVolume { OFF, TRACK, ALBUM }

data class SoundAliveSettings(
    val preset: SoundPreset = SoundPreset.NORMAL,
    val customGains: List<Float> = List(EQ_BANDS_HZ.size) { 0f },
    val customBass: Int = 0,
    val customVirtualizer: Int = 0,
    /** Gain before the EQ, -12..+12 dB (needs DynamicsProcessing; added to the bands otherwise). */
    val preampDb: Float = 0f,
    /** -1 = left only, 0 = centre, 1 = right only (needs DynamicsProcessing). */
    val balance: Float = 0f,
    val smartVolume: SmartVolume = SmartVolume.OFF,
) {
    fun gains(): FloatArray = if (preset == SoundPreset.CUSTOM) customGains.toFloatArray() else preset.gainsDb
    fun bass(): Int = if (preset == SoundPreset.CUSTOM) customBass else preset.bassBoost
    fun virtualizer(): Int = if (preset == SoundPreset.CUSTOM) customVirtualizer else preset.virtualizer

    fun save(prefs: SharedPreferences) {
        prefs.edit()
            .putString("preset", preset.name)
            .putString("gains10", customGains.joinToString(","))
            .putInt("bass", customBass)
            .putInt("virt", customVirtualizer)
            .putFloat("preamp", preampDb)
            .putFloat("balance", balance)
            .putString("smart_volume", smartVolume.name)
            .apply()
    }

    companion object {
        fun load(prefs: SharedPreferences): SoundAliveSettings {
            fun floats(key: String) = prefs.getString(key, null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
            // Before the 10-band EQ, the User EQ had the S4's 7 bands; carry those settings over.
            val gains = floats("gains10")?.takeIf { it.size == EQ_BANDS_HZ.size }
                ?: floats("gains")?.takeIf { it.size == S4_BANDS_HZ.size }
                    ?.let { old -> resample(S4_BANDS_HZ, old.toFloatArray(), EQ_BANDS_HZ).map { it.roundToInt().toFloat() } }
                ?: List(EQ_BANDS_HZ.size) { 0f }
            return SoundAliveSettings(
                preset = runCatching { SoundPreset.valueOf(prefs.getString("preset", "NORMAL")!!) }
                    .getOrDefault(SoundPreset.NORMAL),
                customGains = gains,
                customBass = prefs.getInt("bass", 0),
                customVirtualizer = prefs.getInt("virt", 0),
                preampDb = prefs.getFloat("preamp", 0f),
                balance = prefs.getFloat("balance", 0f),
                smartVolume = runCatching { SmartVolume.valueOf(prefs.getString("smart_volume", "OFF")!!) }
                    .getOrDefault(SmartVolume.OFF),
            )
        }
    }
}

/** Gain at [hz], interpolated on a log-frequency scale between [bandsHz]. */
fun interpolate(bandsHz: IntArray, gains: FloatArray, hz: Float): Float {
    if (hz <= bandsHz.first()) return gains.first()
    if (hz >= bandsHz.last()) return gains.last()
    val i = bandsHz.indexOfFirst { it >= hz }
    val lo = ln(bandsHz[i - 1].toFloat()); val hi = ln(bandsHz[i].toFloat())
    val t = (ln(hz) - lo) / (hi - lo)
    return gains[i - 1] + (gains[i] - gains[i - 1]) * t
}

fun resample(fromHz: IntArray, gains: FloatArray, toHz: IntArray) =
    FloatArray(toHz.size) { interpolate(fromHz, gains, toHz[it].toFloat()) }

/**
 * Applies SoundAlive to an audio session. The EQ, preamp and balance use [DynamicsProcessing]
 * (with a limiter so boosts don't clip); if a device can't create it, the classic [Equalizer] is
 * used instead and balance is unavailable. Effects act on the whole session, so a second player
 * sharing the session (crossfade) gets the same sound.
 */
class SoundAliveEngine {
    private var dp: DynamicsProcessing? = null
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virt: Virtualizer? = null
    private var loudness: LoudnessEnhancer? = null
    private var boostMb = 0
    private var last = SoundAliveSettings()

    /** True when the 10-band EQ with preamp and balance is active. */
    val advanced: Boolean get() = dp != null

    fun attach(audioSessionId: Int, settings: SoundAliveSettings) {
        release()
        if (audioSessionId == 0) return
        dp = runCatching { createDynamics(audioSessionId) }.onFailure { log(it) }.getOrNull()
        if (dp == null) eq = runCatching { Equalizer(0, audioSessionId) }.onFailure { log(it) }.getOrNull()
        bass = runCatching { BassBoost(0, audioSessionId) }.onFailure { log(it) }.getOrNull()
        virt = runCatching { Virtualizer(0, audioSessionId) }.onFailure { log(it) }.getOrNull()
        loudness = runCatching { LoudnessEnhancer(audioSessionId) }.onFailure { log(it) }.getOrNull()
        apply(settings)
        setBoost(boostMb)
    }

    private fun createDynamics(session: Int): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,                       // stereo
            true, EQ_BANDS_HZ.size,  // pre-EQ
            false, 0,                // no multi-band compressor
            false, 0,                // no post-EQ
            true,                    // limiter
        ).build()
        return DynamicsProcessing(0, session, config)
    }

    fun apply(s: SoundAliveSettings) {
        last = s
        val gains = s.gains()
        dp?.let { d ->
            runCatching {
                val bands = DynamicsProcessing.Eq(true, true, EQ_BANDS_HZ.size)
                EQ_BANDS_HZ.indices.forEach { i ->
                    val cutoff = if (i == EQ_BANDS_HZ.lastIndex) 20_000f
                    else sqrt(EQ_BANDS_HZ[i].toFloat() * EQ_BANDS_HZ[i + 1])
                    bands.setBand(i, DynamicsProcessing.EqBand(true, cutoff, gains[i]))
                }
                d.setPreEqAllChannelsTo(bands)
                // Fast limiter just under 0 dBFS so EQ / preamp boosts don't clip.
                d.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f))
                val (left, right) = balanceDb(s.balance)
                d.setInputGainbyChannel(0, s.preampDb + left)
                d.setInputGainbyChannel(1, s.preampDb + right)
                d.enabled = gains.any { it != 0f } || s.preampDb != 0f || s.balance != 0f
            }.onFailure { log(it) }
        }
        eq?.let { e ->
            runCatching {
                val range = e.bandLevelRange
                for (band in 0 until e.numberOfBands) {
                    val hz = e.getCenterFreq(band.toShort()) / 1000f
                    val db = interpolate(EQ_BANDS_HZ, gains, hz) + s.preampDb
                    e.setBandLevel(band.toShort(), (db * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt()).toShort())
                }
                e.enabled = gains.any { it != 0f } || s.preampDb != 0f
            }.onFailure { log(it) }
        }
        bass?.let { b ->
            runCatching {
                val strength = s.bass()
                if (b.strengthSupported) b.setStrength(strength.toShort())
                b.enabled = strength > 0
            }.onFailure { log(it) }
        }
        virt?.let { v ->
            runCatching {
                val strength = s.virtualizer()
                if (v.strengthSupported) v.setStrength(strength.toShort())
                v.enabled = strength > 0
            }.onFailure { log(it) }
        }
    }

    /** Positive Smart volume gain (millibels), applied with LoudnessEnhancer, which limits instead of clipping. */
    fun setBoost(mb: Int) {
        boostMb = mb.coerceAtLeast(0)
        loudness?.let { l ->
            runCatching {
                l.setTargetGain(boostMb)
                l.enabled = boostMb > 0
            }.onFailure { log(it) }
        }
    }

    fun release() {
        listOf(dp, eq, bass, virt, loudness).forEach { runCatching { it?.release() } }
        dp = null; eq = null; bass = null; virt = null; loudness = null
    }

    private fun log(t: Throwable) = Log.w("SoundAlive", "Audio effect unavailable: ${t.message}")

    companion object {
        /** Attenuation (dB) for the left and right channel at [balance]. */
        fun balanceDb(balance: Float): Pair<Float, Float> {
            fun atten(x: Float) = if (x <= 0f) 0f else (20 * log10((1f - x).coerceAtLeast(0.001f)))
            return atten(balance) to atten(-balance)
        }
    }
}
