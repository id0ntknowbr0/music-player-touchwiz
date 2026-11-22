package dev.s4music.player

import android.app.Application
import android.widget.FrameLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.s4music.player.data.Library
import dev.s4music.player.ui.LocalUi
import dev.s4music.player.ui.NowPlayingScreen
import dev.s4music.player.ui.PlayerUiState
import dev.s4music.player.ui.PlayerViewModel
import dev.s4music.player.ui.QueueEntry
import dev.s4music.player.ui.UiActions
import dev.s4music.player.ui.theme.S4Theme
import dev.s4music.player.widget.WidgetState
import dev.s4music.player.widget.WidgetUpdater
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class ScreenshotTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun nowPlaying() {
        val state = PlayerUiState(
            connected = true, mediaId = "uri:x", title = "Track 01", artist = "", album = "",
            isPlaying = false, positionMs = 92_000, durationMs = 329_000,
            repeatMode = Player.REPEAT_MODE_OFF,
            queue = listOf(QueueEntry("1", "Track 01", ""), QueueEntry("2", "Track 02", "")), index = 0,
        )
        // Don't connect to the real playback service in this off-device render.
        org.robolectric.Shadows.shadowOf(app).declareComponentUnbindable(
            android.content.ComponentName(app, dev.s4music.player.playback.PlaybackService::class.java))
        val vm = PlayerViewModel(app)
        val ui = UiActions(app, vm)
        captureRoboImage("build/screens/now_playing.png") {
            S4Theme { CompositionLocalProvider(LocalUi provides ui) { NowPlayingScreen(state, Library.EMPTY, onClose = {}) } }
        }
    }

    @Test
    fun library() {
        org.robolectric.Shadows.shadowOf(app).declareComponentUnbindable(
            android.content.ComponentName(app, dev.s4music.player.playback.PlaybackService::class.java))
        val vm = PlayerViewModel(app)
        val ui = UiActions(app, vm)
        val names = listOf("Add Space and Time" to "Blush/Blink", "Breaking Silence" to "Radiance", "Jam Beats Vol I" to "DJ Jam Beats",
            "Over the horizon" to "Samsung", "Passing Thru Fears" to "Invoke", "Point of No Return" to "Adverse to Reaction",
            "Sessions from the Loft" to "Midnight Passing", "Stronghold Syndrome" to "Brice Thomas", "Tidal" to "Waves", "Zero" to "Nil")
        val songs = names.mapIndexed { i, (t, a) ->
            dev.s4music.player.data.Song(i.toLong() + 1, t, a, i.toLong(), "Album $i", i.toLong(), "Pop", 2013, 1, 200_000, "Music", 0, "/sdcard/Music/$t.mp3", "$t.mp3")
        }
        val lib = dev.s4music.player.data.Library(songs, emptyList(), emptyList(), emptyList(), emptyList())
        val state = PlayerUiState(connected = true, mediaId = "4", title = "Over the horizon", artist = "Samsung",
            isPlaying = true, positionMs = 15_000, durationMs = 259_000, queue = listOf(QueueEntry("4", "Over the horizon", "Samsung")), index = 0)
        captureRoboImage("build/screens/library.png") {
            S4Theme {
                CompositionLocalProvider(LocalUi provides ui) {
                    androidx.compose.foundation.layout.Column {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f)) {
                            dev.s4music.player.ui.LibraryScreen(lib, false, emptyList(), 4L)
                        }
                        dev.s4music.player.ui.MiniPlayer(state, lib, onOpen = {})
                    }
                }
            }
        }
    }

    @Test
    fun libraryPausedOneSong() {
        org.robolectric.Shadows.shadowOf(app).declareComponentUnbindable(
            android.content.ComponentName(app, dev.s4music.player.playback.PlaybackService::class.java))
        val vm = PlayerViewModel(app)
        val ui = UiActions(app, vm)
        val names = listOf("Add Space and Time" to "Blush/Blink", "Breaking Silence" to "Radiance", "Jam Beats Vol I" to "DJ Jam Beats",
            "Over the horizon" to "Samsung", "Passing Thru Fears" to "Invoke", "Point of No Return" to "Adverse to Reaction",
            "Sessions from the Loft" to "Midnight Passing", "Stronghold Syndrome" to "Brice Thomas", "Tidal" to "Waves", "Zero" to "Nil")
        val songs = names.mapIndexed { i, (t, a) ->
            dev.s4music.player.data.Song(i.toLong() + 1, t, a, i.toLong(), "Album $i", i.toLong(), "Pop", 2013, 1, 200_000, "Music", 0, "/sdcard/Music/$t.mp3", "$t.mp3")
        }
        val lib = dev.s4music.player.data.Library(songs.filter { it.id == 4L }, emptyList(), emptyList(), emptyList(), emptyList())
        val state = PlayerUiState(connected = true, mediaId = "4", title = "Over the horizon", artist = "Samsung",
            isPlaying = false, positionMs = 15_000, durationMs = 259_000, queue = listOf(QueueEntry("4", "Over the horizon", "Samsung")), index = 0)
        captureRoboImage("build/screens/library_paused.png") {
            S4Theme {
                CompositionLocalProvider(LocalUi provides ui) {
                    androidx.compose.foundation.layout.Column {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f)) {
                            dev.s4music.player.ui.LibraryScreen(lib, false, emptyList(), 4L)
                        }
                        dev.s4music.player.ui.MiniPlayer(state, lib, onOpen = {})
                    }
                }
            }
        }
    }

    @Test
    fun nowPlayingFavourite() {
        org.robolectric.Shadows.shadowOf(app).declareComponentUnbindable(
            android.content.ComponentName(app, dev.s4music.player.playback.PlaybackService::class.java))
        val ui = UiActions(app, PlayerViewModel(app))
        val song = dev.s4music.player.data.Song(4, "Over the horizon", "Samsung", 1, "Galaxy", 1, "Pop", 2013, 1,
            259_000, "Music", 0, "/sdcard/Music/x.mp3", "x.mp3")
        dev.s4music.player.data.Favourites.toggle(4)
        val lib = dev.s4music.player.data.Library(listOf(song), emptyList(), emptyList(), emptyList(), emptyList())
        val state = PlayerUiState(connected = true, mediaId = "4", title = song.title, artist = song.artist, album = song.album,
            positionMs = 15_000, durationMs = 259_000, queue = listOf(QueueEntry("4", song.title, song.artist)), index = 0)
        captureRoboImage("build/screens/now_playing_favourite.png") {
            S4Theme { CompositionLocalProvider(LocalUi provides ui) { NowPlayingScreen(state, lib, onClose = {}) } }
        }
    }

    @Test
    fun musicSquare() {
        org.robolectric.Shadows.shadowOf(app).declareComponentUnbindable(
            android.content.ComponentName(app, dev.s4music.player.playback.PlaybackService::class.java))
        val ui = UiActions(app, PlayerViewModel(app))
        val genres = listOf("Metal", "Rock", "Pop", "Dance", "Ambient", "Jazz", "Blues", "Classical", "Reggae", "Pop",
            "Pop", "Rock", "Hip Hop", "Folk", "Disco", "House", "Soul", "Country", "Pop", "Dance")
        val songs = genres.mapIndexed { i, g ->
            dev.s4music.player.data.Song(i + 1L, "Song ${i + 1}", "Artist", 1, "Album", 1, g, 2013, 1, 200_000,
                "Music", 0, "/sdcard/Music/$i.mp3", "$i.mp3")
        }
        val lib = dev.s4music.player.data.MediaStoreRepository.build(songs, dev.s4music.player.data.SongSort.TITLE)
        captureRoboImage("build/screens/music_square.png") {
            S4Theme {
                CompositionLocalProvider(LocalUi provides ui) {
                    dev.s4music.player.ui.MusicSquareTab(lib, null) {}
                }
            }
        }
    }

    @Test
    fun settings() {
        val vm = dev.s4music.player.ui.LibraryViewModel(app)
        captureRoboImage("build/screens/settings.png") {
            S4Theme { dev.s4music.player.ui.SettingsScreen(vm, onBack = {}) }
        }
    }

    private fun renderWidget(layout: Int, w: Int, h: Int, file: String) {
        val state = WidgetState("Over the Horizon", "Samsung", null, playing = true, album = "Galaxy S4 Sounds")
        val views = WidgetUpdater.build(app, state, null, layout)
        val parent = FrameLayout(app)
        val v = views.apply(app, parent)
        parent.addView(v, FrameLayout.LayoutParams(w, h))
        parent.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, w, h)
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        parent.draw(android.graphics.Canvas(bmp))
        bmp.captureRoboImage(file)
    }

    @Test
    fun widget() = renderWidget(R.layout.widget_player, 720, 200, "build/screens/widget.png")

    @Test
    fun widgetLarge() = renderWidget(R.layout.widget_player_large, 720, 400, "build/screens/widget_large.png")

    @Test
    fun widgetSmall() = renderWidget(R.layout.widget_play_pause, 180, 180, "build/screens/widget_small.png")
}
