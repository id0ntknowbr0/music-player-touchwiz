package dev.s4music.player

import dev.s4music.player.data.M3u
import dev.s4music.player.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class M3uTest {
    private fun song(id: Long, path: String, title: String = "T$id", artist: String = "A") = Song(
        id, title, artist, 1, "Album", 1, "Pop", 2013, id.toInt(), 200_000, path.substringBeforeLast('/'), 0,
        path, path.substringAfterLast('/'),
    )

    private val library = listOf(
        song(1, "/storage/emulated/0/Music/Daft Punk/One More Time.mp3", "One More Time", "Daft Punk"),
        song(2, "/storage/emulated/0/Music/Live/01 Intro.flac"),
        song(3, "/storage/emulated/0/Music/Other/01 Intro.flac"),
        song(4, "/storage/emulated/0/Music/x/Unrelated name.mp3", "Breathe", "Telepopmusik"),
    )

    @Test
    fun matchesAbsoluteRelativeWindowsAndFileUrls() {
        val text = """
            #EXTM3U
            /storage/emulated/0/Music/Daft Punk/One More Time.mp3
            ../Live/01 Intro.flac
            C:\Users\me\Music\Other\01 Intro.flac
            file:///sdcard/Music/Daft%20Punk/One%20More%20Time.mp3
        """.trimIndent()
        val parsed = M3u.parse("p", text)!!
        val r = M3u.resolve(parsed.entries, library)
        assertEquals(listOf(1L, 2L, 3L, 1L), r.songIds)
        assertEquals(emptyList<String>(), r.unmatched)
    }

    @Test
    fun fallsBackToExtinfAndReportsUnmatched() {
        val text = "#EXTM3U\n#EXTINF:240,Telepopmusik - Breathe\nD:\\old\\breathe.wma\nhttp://radio.example/stream\n"
        val r = M3u.resolve(M3u.parse("p", text)!!.entries, library)
        assertEquals(listOf(4L), r.songIds)
        assertEquals(listOf("http://radio.example/stream"), r.unmatched)
    }

    @Test
    fun roundTripsExport() {
        val exported = M3u.render(library)
        val r = M3u.resolve(M3u.parse("p", exported)!!.entries, library)
        assertEquals(library.map { it.id }, r.songIds)
    }

    @Test
    fun emptyPlaylistIsRejected() {
        assertNull(M3u.parse("p", "#EXTM3U\n# just a comment\n"))
    }

    @Test
    fun exportFileNamesAreSafe() {
        assertEquals("Rock_Pop_ 2024.m3u8", M3u.fileNameFor("Rock/Pop: 2024"))
    }
}
