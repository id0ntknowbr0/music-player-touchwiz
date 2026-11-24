package dev.s4music.player

import dev.s4music.player.data.MoodAnalyzer
import dev.s4music.player.data.MoodCell
import dev.s4music.player.data.MoodClassifier
import dev.s4music.player.data.MoodFeatures
import dev.s4music.player.data.RingtoneMaker
import dev.s4music.player.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
class Phase3Test {
    private fun song(genre: String, name: String = "a.mp3") =
        Song(1, "T", "A", 1, "Al", 1, genre, 2013, 1, 200_000, "Music", 0, "/sdcard/Music/$name", name)

    // ---- MP3 cutting ----

    /** MPEG-1 Layer III, 128 kbps, 44.1 kHz, no padding: 417-byte frames of 26.12 ms. */
    private fun frame(fill: Int, marker: String? = null): ByteArray {
        val f = ByteArray(417) { fill.toByte() }
        f[0] = 0xFF.toByte(); f[1] = 0xFB.toByte(); f[2] = 0x90.toByte(); f[3] = 0x00
        marker?.toByteArray()?.copyInto(f, 36)
        return f
    }

    private fun mp3(frames: Int): ByteArray {
        val out = ByteArrayOutputStream()
        // ID3v2 header with a 20-byte (syncsafe) body
        out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 20))
        out.write(ByteArray(20))
        out.write(frame(0x11, "Xing"))
        repeat(frames) { out.write(frame(0x22)) }
        return out.toByteArray()
    }

    @Test
    fun cutsMp3OnFrameBoundariesAndDropsTagsAndXing() {
        val clip = RingtoneMaker.cutMp3(mp3(100), 1_000, 2_000)
        assertEquals(0, clip.size % 417)
        val frames = clip.size / 417
        assertTrue("got $frames frames", frames in 37..39)   // 1 s ≈ 38.3 frames
        assertEquals(0xFF.toByte(), clip[0])
        assertTrue(clip.none { it == 0x11.toByte() })          // Xing frame not copied
    }

    @Test
    fun cutFromStartKeepsEverythingUpToEnd() {
        val clip = RingtoneMaker.cutMp3(mp3(100), 0, 60_000)
        assertEquals(100 * 417, clip.size)
    }

    @Test
    fun onlyMp3AndAacCanBeTrimmed() {
        assertTrue(RingtoneMaker.canTrim(song("Pop", "a.mp3")))
        assertTrue(RingtoneMaker.canTrim(song("Pop", "a.m4a")))
        assertTrue(!RingtoneMaker.canTrim(song("Pop", "a.flac")))
    }

    // ---- Music Square ----

    @Test
    fun genreAloneClassifies() {
        assertEquals(MoodCell(4, 0), MoodClassifier.cell(song("Heavy Metal"), null))   // exciting, passionate
        assertEquals(MoodCell(0, 2), MoodClassifier.cell(song("Ambient"), null))       // calm, middle
        assertEquals(4, MoodClassifier.cell(song("Dance Pop"), null)!!.x)              // longest match "dance" wins
        assertNull(MoodClassifier.cell(song("Unknown genre"), null))
    }

    @Test
    fun tempoAndLevelMoveSongsWithoutGenre() {
        val fast = MoodFeatures(tagsRead = true, analysed = true, tempo = 175f, rmsDb = -9f, brightness = 0.9f)
        val slow = MoodFeatures(tagsRead = true, analysed = true, tempo = 65f, rmsDb = -28f, brightness = 0.1f)
        assertEquals(MoodCell(4, 4), MoodClassifier.cell(song(""), fast))
        assertEquals(MoodCell(0, 0), MoodClassifier.cell(song(""), slow))
        assertNotNull(MoodClassifier.cell(song(""), MoodFeatures(tagsRead = true, bpmTag = 120f)))
    }

    @Test
    fun squareLightsOnlyWhenItsSongsPlay() {
        fun s(id: Long) = Song(id, "T$id", "A", 1, "Al", 1, "", 2013, 1, 200_000, "Music", 0, "/x/$id.mp3", "$id.mp3")
        val corner = MoodCell(0, 0).index
        val diagonal = MoodCell(1, 1).index
        val far = MoodCell(4, 4).index
        val grid = dev.s4music.player.ui.MoodGrid(mapOf(
            corner to listOf(s(1), s(2), s(3)),
            diagonal to listOf(s(4), s(5)),
            far to (100L until 130L).map { s(it) },
        ), 0)
        // A square with songs plays only its own songs and is the only one lit.
        val own = dev.s4music.player.ui.moodPick(grid, listOf(corner))
        assertEquals(setOf(1L, 2L, 3L), own.songs.map { it.id }.toSet())
        assertEquals(setOf(corner), own.lit)
        // An empty square doesn't borrow songs from its neighbours: nothing plays, nothing lights.
        val empty = dev.s4music.player.ui.moodPick(grid, listOf(MoodCell(1, 0).index))
        assertTrue(empty.songs.isEmpty() && empty.lit.isEmpty())
        // Dragging across a full and an empty square lights only the full one.
        assertEquals(setOf(far), dev.s4music.player.ui.moodPick(grid, listOf(far, MoodCell(3, 3).index)).lit)
        // Nothing placed anywhere: nothing plays, nothing lights.
        val none = dev.s4music.player.ui.moodPick(dev.s4music.player.ui.MoodGrid(emptyMap(), 5), listOf(corner))
        assertTrue(none.songs.isEmpty() && none.lit.isEmpty())
    }

    @Test
    fun findsTempoOfAClickTrack() {
        val rate = 22_050
        val seconds = 12
        val bpm = 128.0
        val x = FloatArray(rate * seconds)
        val beat = (60.0 / bpm * rate).toInt()
        for (start in 0 until x.size step beat) {
            for (i in 0 until 400) if (start + i < x.size) {
                x[start + i] = (sin(2 * PI * 1_000 * i / rate) * (1 - i / 400.0)).toFloat() * 0.8f
            }
        }
        val tempo = MoodAnalyzer.estimateTempo(x, x.size, rate)!!
        assertTrue("tempo $tempo", abs(tempo - bpm) < 4)
        val r = MoodAnalyzer.measure(x, x.size, rate)
        assertTrue(r.rmsDb < -10f)
    }

    @Test
    fun silenceHasNoTempo() {
        assertNull(MoodAnalyzer.estimateTempo(FloatArray(22_050 * 5), 22_050 * 5, 22_050))
    }
}
