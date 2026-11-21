@file:Suppress("DEPRECATION") // Virtualizer: deprecated in API 33 but still functional; no drop-in replacement for an app-level effect.

package dev.s4music.player.playback

import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.util.Log
import androidx.annotation.StringRes
import dev.s4music.player.R
import kotlin.math.ln

/** The S4's 7-band user EQ frequencies. Mapped onto whatever bands the device equaliser has. */
val S4_BANDS_HZ = intArrayOf(63, 150, 400, 1000, 2500, 6300, 16000)

private fun g(vararg v: Int) = FloatArray(v.size) { v[it].toFloat() }

/** SoundAlive presets from the S4 music player, re-tuned by ear for Android's audio effects. */
enum class SoundPreset(
    @StringRes val label: Int,
    val gainsDb: FloatArray,
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
    CUSTOM(R.string.preset_user_eq, g(0, 0, 0, 0, 0, 0, 0)),
}

data class SoundAliveSettings(
    val preset: SoundPreset = SoundPreset.NORMAL,
    val customGains: List<Float> = List(S4_BANDS_HZ.size) { 0f },
    val customBass: Int = 0,
    val customVirtualizer: Int = 0,
) {
    fun gains(): FloatArray = if (preset == SoundPreset.CUSTOM) customGains.toFloatArray() else preset.gainsDb
    fun bass(): Int = if (preset == SoundPreset.CUSTOM) customBass else preset.bassBoost
    fun virtualizer(): Int = if (preset == SoundPreset.CUSTOM) customVirtualizer else preset.virtualizer

    fun save(prefs: SharedPreferences) {
        prefs.edit()
            .putString("preset", preset.name)
            .putString("gains", customGains.joinToString(","))
            .putInt("bass", customBass)
            .putInt("virt", customVirtualizer)
            .apply()
    }

    companion object {
        fun load(prefs: SharedPreferences): SoundAliveSettings {
            val gains = prefs.getString("gains", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
            return SoundAliveSettings(
                preset = runCatching { SoundPreset.valueOf(prefs.getString("preset", "NORMAL")!!) }
                    .getOrDefault(SoundPreset.NORMAL),
                customGains = gains?.takeIf { it.size == S4_BANDS_HZ.size } ?: List(S4_BANDS_HZ.size) { 0f },
                customBass = prefs.getInt("bass", 0),
                customVirtualizer = prefs.getInt("virt", 0),
            )
        }
    }
}

/** Applies SoundAlive settings to the player's audio session with the platform audio effects. */
class SoundAliveEngine {
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virt: Virtualizer? = null

    fun attach(audioSessionId: Int, settings: SoundAliveSettings) {
        release()
        if (audioSessionId == 0) return
        eq = runCatching { Equalizer(0, audioSessionId) }.onFailure { log(it) }.getOrNull()
        bass = runCatching { BassBoost(0, audioSessionId) }.onFailure { log(it) }.getOrNull()
        virt = runCatching { Virtualizer(0, audioSessionId) }.onFailure { log(it) }.getOrNull()
        apply(settings)
    }

    fun apply(s: SoundAliveSettings) {
        eq?.let { e ->
            runCatching {
                val gains = s.gains()
                val range = e.bandLevelRange
                for (band in 0 until e.numberOfBands) {
                    val hz = e.getCenterFreq(band.toShort()) / 1000f
                    val mb = (interpolate(gains, hz) * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt())
                    e.setBandLevel(band.toShort(), mb.toShort())
                }
                e.enabled = gains.any { it != 0f }
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

    fun release() {
        listOf(eq, bass, virt).forEach { runCatching { it?.release() } }
        eq = null; bass = null; virt = null
    }

    private fun log(t: Throwable) = Log.w("SoundAlive", "Audio effect unavailable: ${t.message}")

    companion object {
        /** Gain at [hz], interpolated on a log-frequency scale between the 7 S4 bands. */
        fun interpolate(gains: FloatArray, hz: Float): Float {
            val bands = S4_BANDS_HZ
            if (hz <= bands.first()) return gains.first()
            if (hz >= bands.last()) return gains.last()
            val i = bands.indexOfFirst { it >= hz }
            val lo = ln(bands[i - 1].toFloat()); val hi = ln(bands[i].toFloat())
            val t = (ln(hz) - lo) / (hi - lo)
            return gains[i - 1] + (gains[i] - gains[i - 1]) * t
        }
    }
}
