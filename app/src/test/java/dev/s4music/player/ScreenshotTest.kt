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
        val ui = UiActions(vm, {}, {}, {}, {}, {}, {}, {}, {})
        captureRoboImage("build/screens/now_playing.png") {
            S4Theme { CompositionLocalProvider(LocalUi provides ui) { NowPlayingScreen(state, Library.EMPTY, onClose = {}) } }
        }
    }

    @Test
    fun library() {
        org.robolectric.Shadows.shadowOf(app).declareComponentUnbindable(
            android.content.ComponentName(app, dev.s4music.player.playback.PlaybackService::class.java))
        val vm = PlayerViewModel(app)
        val ui = UiActions(vm, {}, {}, {}, {}, {}, {}, {}, {})
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
        val ui = UiActions(vm, {}, {}, {}, {}, {}, {}, {}, {})
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
    fun widget() {
        val views = WidgetUpdater.build(app, WidgetState("Over the Horizon", "Samsung", null, playing = true), null)
        val parent = FrameLayout(app)
        val v = views.apply(app, parent)
        parent.addView(v, FrameLayout.LayoutParams(720, 200))
        parent.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(720, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(200, android.view.View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, 720, 200)
        val bmp = android.graphics.Bitmap.createBitmap(720, 200, android.graphics.Bitmap.Config.ARGB_8888)
        parent.draw(android.graphics.Canvas(bmp))
        bmp.captureRoboImage("build/screens/widget.png")
    }
}
