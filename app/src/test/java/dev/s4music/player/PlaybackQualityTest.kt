package dev.s4music.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.s4music.player.playback.EQ_BANDS_HZ
import dev.s4music.player.playback.ReplayGain
import dev.s4music.player.playback.S4_BANDS_HZ
import dev.s4music.player.playback.SmartVolume
import dev.s4music.player.playback.SoundAliveEngine
import dev.s4music.player.playback.SoundAliveSettings
import dev.s4music.player.playback.SoundPreset
import dev.s4music.player.playback.resample
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.id3.ID3v24Frame
import org.jaudiotagger.tag.id3.ID3v24Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackQualityTest {

    @Test
    fun parsesReplayGainValues() {
        assertEquals(-7.03f, ReplayGain.parseDb("-7.03 dB")!!, 0.001f)
        assertEquals(2.5f, ReplayGain.parseDb("+2,5 dB")!!, 0.001f)
        assertNull(ReplayGain.parseDb("garbage"))
        assertNull(ReplayGain.parseDb("-99 dB"))   // out of range: ignore
    }

    @Test
    fun readsId3TxxxFrames() {
        TagOptionSingleton.getInstance().isAndroid = true
        val tag = ID3v24Tag()
        fun txxx(desc: String, value: String) = ID3v24Frame("TXXX").apply {
            body = FrameBodyTXXX(0.toByte(), desc, value)
        }
        tag.addField(txxx("REPLAYGAIN_TRACK_GAIN", "-6.50 dB"))
        tag.addField(txxx("replaygain_album_gain", "-8.10 dB"))
        val g = ReplayGain.fromTag(tag)
        assertEquals(-6.5f, g.trackDb!!, 0.001f)
        assertEquals(-8.1f, g.albumDb!!, 0.001f)
        assertEquals(-8.1f, g.pick(SmartVolume.ALBUM)!!, 0.001f)
    }

    @Test
    fun readsVorbisComments() {
        val tag = VorbisCommentTag.createNewTag()
        tag.setField(tag.createField("REPLAYGAIN_TRACK_GAIN", "-3.20 dB"))
        val g = ReplayGain.fromTag(tag)
        assertEquals(-3.2f, g.trackDb!!, 0.001f)
        // Album mode falls back to the track gain when there's no album gain.
        assertEquals(-3.2f, g.pick(SmartVolume.ALBUM)!!, 0.001f)
        assertNull(g.pick(SmartVolume.OFF))
    }

    @Test
    fun oldSevenBandUserEqIsCarriedOver() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("t", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("preset", "CUSTOM").putString("gains", "6,4,2,0,-2,-4,-6").commit()
        val s = SoundAliveSettings.load(prefs)
        assertEquals(EQ_BANDS_HZ.size, s.customGains.size)
        assertEquals(6f, s.customGains.first(), 0.001f)     // 31 Hz takes the lowest band's value
        assertEquals(0f, s.customGains[5], 0.001f)          // 1 kHz is an original band
        assertEquals(-6f, s.customGains.last(), 0.001f)

        s.copy(preampDb = 3f, smartVolume = SmartVolume.TRACK).save(prefs)
        val reloaded = SoundAliveSettings.load(prefs)
        assertEquals(s.customGains, reloaded.customGains)
        assertEquals(3f, reloaded.preampDb, 0.001f)
        assertEquals(SmartVolume.TRACK, reloaded.smartVolume)
    }

    @Test
    fun presetsKeepTheirShapeOnTenBands() {
        val rock = SoundPreset.ROCK.gainsDb
        assertEquals(10, rock.size)
        assertEquals(-2f, rock[5], 0.001f)   // 1 kHz dip of the original Rock preset
        val back = resample(EQ_BANDS_HZ, rock, S4_BANDS_HZ)
        assertEquals(5f, back.last(), 0.01f)
    }

    @Test
    fun balanceAttenuatesTheOtherSide() {
        assertEquals(0f to 0f, SoundAliveEngine.balanceDb(0f))
        val (l, r) = SoundAliveEngine.balanceDb(0.5f)
        assertEquals(-6.02f, l, 0.01f)
        assertEquals(0f, r, 0.001f)
        assertEquals(-60f, SoundAliveEngine.balanceDb(-1f).second, 0.01f)
    }
}
