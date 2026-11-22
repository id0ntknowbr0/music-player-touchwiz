package dev.s4music.player

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.s4music.player.data.CoverStore
import dev.s4music.player.data.Favourites
import dev.s4music.player.data.LibraryTabId
import dev.s4music.player.data.MediaStoreRepository
import dev.s4music.player.data.MoodFeatures
import dev.s4music.player.data.MoodStore
import dev.s4music.player.data.Song
import dev.s4music.player.data.SongSort
import dev.s4music.player.ui.LibraryScreen
import dev.s4music.player.ui.LocalUi
import dev.s4music.player.ui.MiniPlayer
import dev.s4music.player.ui.MusicSquareTab
import dev.s4music.player.ui.NowPlayingScreen
import dev.s4music.player.ui.PlayerUiState
import dev.s4music.player.ui.PlayerViewModel
import dev.s4music.player.ui.QueueEntry
import dev.s4music.player.ui.SettingsScreen
import dev.s4music.player.ui.UiActions
import dev.s4music.player.ui.buildMoodGrid
import dev.s4music.player.ui.theme.S4Theme
import dev.s4music.player.widget.WidgetState
import dev.s4music.player.widget.WidgetUpdater
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders the README screenshots into build/showcase/ with a made-up library: invented songs and
 * artists, and abstract cover art drawn here. Run: ./gradlew testDebugUnitTest --tests "*ShowcaseScreens*"
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h760dp-xhdpi")
class ShowcaseScreens {
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private data class DemoAlbum(val id: Long, val title: String, val artist: String, val genre: String,
                                 val year: Int, val colors: IntArray, val style: Int, val songs: List<String>)

    private val albums = listOf(
        DemoAlbum(101, "Neon Harbour", "The Velvet Static", "Synthpop", 2013,
            intArrayOf(0xFF2B1055.toInt(), 0xFFD53A9D.toInt(), 0xFF7597DE.toInt()), 0,
            listOf("Afterglow Avenue", "Static Hearts", "Harbour Lights", "Glass Skyline")),
        DemoAlbum(102, "Paper Lanterns", "Mira Solano", "Indie Pop", 2012,
            intArrayOf(0xFFFF9A3C.toInt(), 0xFFFFD86E.toInt(), 0xFFE2504C.toInt()), 1,
            listOf("Lanterns", "Golden Hour", "Kite Season")),
        DemoAlbum(103, "Coastline Radio", "Kitewire", "Indie Rock", 2013,
            intArrayOf(0xFF0F4C75.toInt(), 0xFF3282B8.toInt(), 0xFFBBE1FA.toInt()), 2,
            listOf("Coastline", "Breakwater", "Saltwater Static", "Radio Silence")),
        DemoAlbum(104, "Slow Orbit", "Halcyon Drift", "Ambient", 2011,
            intArrayOf(0xFF0B132B.toInt(), 0xFF1C2541.toInt(), 0xFF5BC0BE.toInt()), 3,
            listOf("Weightless", "Orbit", "Quiet Satellites")),
        DemoAlbum(105, "Midnight Arcade", "Pixel Saints", "Dance", 2013,
            intArrayOf(0xFF240046.toInt(), 0xFFFF006E.toInt(), 0xFF3A86FF.toInt()), 0,
            listOf("Insert Coin", "High Score", "Night Drive")),
        DemoAlbum(106, "Wildflower Avenue", "June & the Lanterns", "Folk", 2012,
            intArrayOf(0xFF606C38.toInt(), 0xFFDDA15E.toInt(), 0xFFFEFAE0.toInt()), 1,
            listOf("Wildflowers", "Back Porch Song", "Morning Train")),
        DemoAlbum(107, "Brass Monsoon", "Octavia Reyes", "Jazz", 2010,
            intArrayOf(0xFF3D2C2E.toInt(), 0xFFC8963E.toInt(), 0xFFF3E9DC.toInt()), 2,
            listOf("Monsoon Blues", "Blue Hour", "Copper Rain")),
        DemoAlbum(108, "Iron Meridian", "Grey Cathedral", "Metal", 2013,
            intArrayOf(0xFF111111.toInt(), 0xFF8D0801.toInt(), 0xFFBF0603.toInt()), 3,
            listOf("Meridian", "Thunderhead", "Cathedral Fire")),
    )

    private val songs: List<Song> by lazy {
        var id = 1L
        albums.flatMapIndexed { ai, a ->
            a.songs.mapIndexed { ti, t ->
                Song(id++, t, a.artist, 200L + ai, a.title, a.id, a.genre, a.year, ti + 1,
                    (150 + (t.length * 13) % 140) * 1000L, "Music/${a.artist}", 1_700_000_000L + id,
                    "/sdcard/Music/${a.artist}/$t.mp3", "$t.mp3")
            }
        }
    }
    private val library by lazy { MediaStoreRepository.build(songs, SongSort.TITLE) }
    private val current by lazy { songs.first { it.title == "Coastline" } }

    @Before
    fun setUp() {
        shadowOf(app).declareComponentUnbindable(ComponentName(app, dev.s4music.player.playback.PlaybackService::class.java))
        albums.forEach { a ->
            CoverStore.saveForAlbum(app, a.id, songs.filter { it.albumId == a.id }.map { it.id }, cover(a))
        }
        if (!Favourites.contains(current.id)) Favourites.toggle(current.id)
    }

    /** Abstract cover: a gradient plus one of four simple motifs. */
    private fun cover(a: DemoAlbum): ByteArray {
        val size = 600f
        val bmp = Bitmap.createBitmap(size.toInt(), size.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, size, size, a.colors[0], a.colors[1], Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, size, size, p)
        p.shader = null
        when (a.style) {
            0 -> { // sun over a striped horizon
                p.shader = RadialGradient(size / 2, size * 0.55f, size * 0.3f, a.colors[2], 0x00000000, Shader.TileMode.CLAMP)
                c.drawCircle(size / 2, size * 0.55f, size * 0.3f, p)
                p.shader = null
                p.color = a.colors[0]
                for (i in 0 until 6) c.drawRect(0f, size * (0.62f + i * 0.05f), size, size * (0.62f + i * 0.05f) + 6f + i * 3f, p)
            }
            1 -> { // floating circles
                p.color = a.colors[2]
                for (i in 0 until 7) {
                    p.alpha = 70 + i * 20
                    c.drawCircle(size * (0.15f + (i * 0.37f) % 0.75f), size * (0.2f + (i * 0.23f) % 0.65f), size * (0.06f + i * 0.012f), p)
                }
            }
            2 -> { // concentric rings
                p.style = Paint.Style.STROKE
                p.strokeWidth = 10f
                p.color = a.colors[2]
                for (r in 1..8) { p.alpha = 255 - r * 25; c.drawCircle(size * 0.3f, size * 0.7f, r * 42f, p) }
                p.style = Paint.Style.FILL
            }
            else -> { // starburst
                p.color = a.colors[2]
                p.strokeWidth = 5f
                for (i in 0 until 36) {
                    val ang = Math.toRadians(i * 10.0)
                    p.alpha = if (i % 2 == 0) 220 else 90
                    c.drawLine(size / 2, size / 2, size / 2 + (cos(ang) * size).toFloat(), size / 2 + (sin(ang) * size).toFloat(), p)
                }
                p.alpha = 255
                p.color = a.colors[0]
                c.drawCircle(size / 2, size / 2, size * 0.12f, p)
            }
        }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
    }

    private fun ui() = UiActions(app, PlayerViewModel(app))

    private fun playerState(playing: Boolean = true) = PlayerUiState(
        connected = true, mediaId = current.id.toString(), title = current.title, artist = current.artist,
        album = current.album, isPlaying = playing, positionMs = 64_000, durationMs = current.durationMs,
        repeatMode = Player.REPEAT_MODE_ALL,
        queue = library.albums.first { it.id == current.albumId }.songs.map { QueueEntry(it.id.toString(), it.title, it.artist) },
        index = 0,
    )

    /** Album art loads on a background thread; give it a moment before capturing. */
    private fun shoot(name: String) {
        compose.waitForIdle()
        Thread.sleep(1_000)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/showcase/$name.png")
    }

    @Test
    fun songs() {
        compose.setContent {
            S4Theme {
                CompositionLocalProvider(LocalUi provides ui()) {
                    Column {
                        Box(Modifier.weight(1f)) { LibraryScreen(library, false, emptyList(), current.id) }
                        MiniPlayer(playerState(), library, onOpen = {})
                    }
                }
            }
        }
        shoot("01_songs")
    }

    @Test
    fun albums() {
        compose.setContent {
            S4Theme {
                CompositionLocalProvider(LocalUi provides ui()) {
                    Column {
                        Box(Modifier.weight(1f)) {
                            LibraryScreen(library, false, emptyList(), current.id, initialTab = LibraryTabId.ALBUMS)
                        }
                        MiniPlayer(playerState(), library, onOpen = {})
                    }
                }
            }
        }
        shoot("02_albums")
    }

    /** The tab bar scrolled to the end, where the Composers and Music square tabs are. */
    @Test
    fun lastTabs() {
        dev.s4music.player.data.AppSettings.update { it.copy(hiddenTabs = emptySet()) }
        compose.setContent {
            S4Theme {
                CompositionLocalProvider(LocalUi provides ui()) {
                    LibraryScreen(library, false, emptyList(), current.id, initialTab = LibraryTabId.MUSIC_SQUARE)
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        shoot("tabs_end")
        dev.s4music.player.data.AppSettings.update { it.copy(hiddenTabs = dev.s4music.player.data.DEFAULT_HIDDEN_TABS) }
    }

    @Test
    fun nowPlaying() {
        compose.setContent {
            S4Theme { CompositionLocalProvider(LocalUi provides ui()) { NowPlayingScreen(playerState(playing = false), library, onClose = {}) } }
        }
        shoot("03_now_playing")
    }

    @Test
    fun lyrics() {
        File(File(app.filesDir, "lyrics").apply { mkdirs() }, "${current.id}.lrc").writeText(
            """
            [00:40.00]The tide pulls the city lights out to sea
            [00:46.00]Every wave is a song on the radio
            [00:52.00]Turn it up, let the static fall away
            [00:58.00]We were young on the coastline
            [01:03.00]Singing louder than the gulls
            [01:09.00]Coastline, coastline, carry me home
            [01:15.00]Through the salt and the summer glow
            [01:21.00]Coastline, coastline, don't let go
            [01:27.00]Of the radio
            """.trimIndent()
        )
        compose.setContent {
            S4Theme {
                CompositionLocalProvider(LocalUi provides ui()) {
                    NowPlayingScreen(playerState(), library, onClose = {}, startWithLyrics = true)
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Singing louder than the gulls").fetchSemanticsNodes().isNotEmpty() }
        shoot("04_lyrics")
    }

    @Test
    fun musicSquare() {
        // As if "Analyse songs" had run: varied tempo, level and brightness spread songs over the grid.
        MoodStore.put(songs.associate { s ->
            s.id to MoodFeatures(tagsRead = true, analysed = true, tempo = 70f + (s.id * 37 % 110),
                rmsDb = -26f + (s.id * 13 % 18), brightness = (s.id * 29 % 100) / 100f)
        })
        val grid = buildMoodGrid(library.songs, MoodStore.features.value)
        val selection = grid.cells.entries.sortedByDescending { it.value.size }.take(3).map { it.key }
        compose.setContent {
            S4Theme {
                CompositionLocalProvider(LocalUi provides ui()) {
                    MusicSquareTab(library, current.id, actions = {}, initialSelection = selection)
                }
            }
        }
        shoot("05_music_square")
    }

    @Test
    fun settings() {
        compose.setContent { S4Theme { SettingsScreen(dev.s4music.player.ui.LibraryViewModel(app), onBack = {}) } }
        shoot("06_settings")
    }

    /**
     * The widget picker's preview images (res/drawable-nodpi/widget_preview*.webp): the app's own
     * widgets with nothing playing, so they show only "Music Player" and the default cover.
     */
    @Test
    fun widgetPreviews() {
        fun render(layout: Int, width: Int, height: Int, file: String) {
            val parent = FrameLayout(app)
            parent.addView(WidgetUpdater.build(app, WidgetState(), null, layout).apply(app, parent),
                FrameLayout.LayoutParams(width, height))
            parent.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY))
            parent.layout(0, 0, width, height)
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            parent.draw(Canvas(bmp))
            bmp.captureRoboImage("build/showcase/$file.png")
        }
        render(R.layout.widget_player, 720, 200, "widget_preview")
        render(R.layout.widget_player_large, 720, 400, "widget_preview_large")
    }

    /** All three widgets on a plain wallpaper, like a home screen. */
    @Test
    fun widgets() {
        val w = 720
        val h = 760
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bg = Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF16324F.toInt(), 0xFF2C6E8F.toInt(), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        val art = Bitmap.createScaledBitmap(
            android.graphics.BitmapFactory.decodeByteArray(cover(albums[2]), 0, cover(albums[2]).size), 400, 400, true)
        val state = WidgetState(current.title, current.artist, current.albumId, current.id, playing = true,
            repeatMode = Player.REPEAT_MODE_ALL, album = current.album)

        fun draw(layout: Int, x: Int, y: Int, width: Int, height: Int) {
            val parent = FrameLayout(app)
            parent.addView(WidgetUpdater.build(app, state, art, layout).apply(app, parent), FrameLayout.LayoutParams(width, height))
            parent.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY))
            parent.layout(0, 0, width, height)
            canvas.save(); canvas.translate(x.toFloat(), y.toFloat()); parent.draw(canvas); canvas.restore()
        }
        draw(R.layout.widget_player_large, 24, 40, 672, 380)
        draw(R.layout.widget_player, 24, 450, 672, 170)
        draw(R.layout.widget_play_pause, 24, 650, 96, 96)
        bmp.captureRoboImage("build/showcase/07_widgets.png")
    }
}
