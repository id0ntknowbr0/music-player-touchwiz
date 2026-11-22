package dev.s4music.player

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import dev.s4music.player.data.Song
import dev.s4music.player.ui.BackStackSaver
import dev.s4music.player.ui.LibraryViewModel
import dev.s4music.player.ui.PlayerViewModel
import dev.s4music.player.ui.S4MusicApp
import dev.s4music.player.ui.Screen
import dev.s4music.player.ui.SongSource
import dev.s4music.player.ui.theme.S4Theme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The phone's back button and back gesture return to where you were, not to the Songs tab. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h760dp-xhdpi")
class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private val songs = listOf(
        song(1, "Breakwater", "Music/Rainy Days"),
        song(2, "Coastline", "Music/Rainy Days"),
        song(3, "Golden Hour", "Music/Sunny Mornings"),
    )

    private fun song(id: Long, title: String, folder: String) = Song(id, title, "Kitewire", 1, "Coastline Radio", 1,
        "Indie", 2013, id.toInt(), 200_000, folder, 1_700_000_000L, "/sdcard/$folder/$title.mp3", "$title.mp3")

    @Before
    fun setUp() {
        shadowOf(app).declareComponentUnbindable(ComponentName(app, dev.s4music.player.playback.PlaybackService::class.java))
    }

    private fun pressBack() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private fun showApp(restoration: StateRestorationTester? = null) {
        val libraryVm = LibraryViewModel(app).apply { showSongs(songs) }
        val playerVm = PlayerViewModel(app)
        val openPlayer = mutableStateOf(false)
        val content = @androidx.compose.runtime.Composable { S4Theme { S4MusicApp(libraryVm, playerVm, openPlayer) } }
        if (restoration != null) restoration.setContent(content) else compose.setContent(content)
    }

    private fun openRainyDaysFolder() {
        compose.onNodeWithText("Folders").performScrollTo().performClick()
        compose.onNodeWithText("Rainy Days").performClick()
        compose.onNodeWithText("Breakwater").assertIsDisplayed()          // inside the folder
    }

    @Test
    fun backFromAFolderReturnsToTheFoldersTab() {
        showApp()
        openRainyDaysFolder()
        pressBack()
        // The Folders tab again, not the Songs tab (which would list the songs instead).
        compose.onNodeWithText("Sunny Mornings").assertIsDisplayed()
        compose.onNodeWithText("Rainy Days").assertIsDisplayed()
    }

    @Test
    fun openFolderSurvivesRotation() {
        val restoration = StateRestorationTester(compose)
        showApp(restoration)
        openRainyDaysFolder()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Breakwater").assertIsDisplayed()
        pressBack()
        compose.onNodeWithText("Sunny Mornings").assertIsDisplayed()
    }

    private fun showPlayer(startWithLyrics: Boolean, onClose: () -> Unit = {}) {
        val library = dev.s4music.player.data.MediaStoreRepository.build(songs, dev.s4music.player.data.SongSort.TITLE)
        val song = songs[1]
        val ps = dev.s4music.player.ui.PlayerUiState(connected = true, mediaId = song.id.toString(), title = song.title,
            artist = song.artist, album = song.album, durationMs = song.durationMs,
            queue = listOf(dev.s4music.player.ui.QueueEntry(song.id.toString(), song.title, song.artist)))
        compose.setContent {
            S4Theme {
                androidx.compose.runtime.CompositionLocalProvider(
                    dev.s4music.player.ui.LocalUi provides dev.s4music.player.ui.UiActions(app, PlayerViewModel(app)),
                ) { dev.s4music.player.ui.NowPlayingScreen(ps, library, onClose, startWithLyrics) }
            }
        }
    }

    /** Lyrics open by tapping the cover; the three-dots menu no longer repeats them. */
    @Test
    fun playerMenuHasNoLyricsItem() {
        showPlayer(startWithLyrics = false)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Add to playlist").assertExists()   // the menu is open (a pop-up)
        compose.onNodeWithText("Lyrics").assertDoesNotExist()
    }

    @Test
    fun backClosesLyricsBeforeThePlayer() {
        var closed = false
        showPlayer(startWithLyrics = true) { closed = true }
        compose.onNodeWithText("Import").assertIsDisplayed()
        pressBack()
        compose.onNodeWithText("Import").assertDoesNotExist()
        assertEquals(false, closed)
    }

    @Test
    fun backStackRoundTrips() {
        val stack = listOf(
            Screen.Library, Screen.Search,
            Screen.Songs("Rainy | Days", SongSource.Folder("Music/Rainy | Days")),
            Screen.Songs("Mix", SongSource.UserPlaylist(7)), Screen.Songs("Faves", SongSource.Favourites),
            Screen.Songs("Album", SongSource.Album(3)), Screen.Settings,
        )
        assertEquals(stack, BackStackSaver.restore(BackStackSaver.save(stack)))
        assertEquals(listOf(Screen.Library), BackStackSaver.restore(listOf("nonsense")))
    }
}
