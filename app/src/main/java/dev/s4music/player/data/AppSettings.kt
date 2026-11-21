package dev.s4music.player.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Order of the Songs tab and of genre / folder song lists. */
enum class SongSort { TITLE, ARTIST, ALBUM, DATE_ADDED }

/** Library tabs, in display order. [key] is what gets persisted. */
enum class LibraryTabId(val key: String) {
    SONGS("songs"), PLAYLISTS("playlists"), ALBUMS("albums"), ARTISTS("artists"), GENRES("genres"), FOLDERS("folders"),
    COMPOSERS("composers"), MUSIC_SQUARE("music_square");

    companion object {
        fun fromKey(key: String) = entries.firstOrNull { it.key == key }
    }
}

data class Settings(
    /** Tracks shorter than this are hidden (voice notes, ringtones). 0 = show everything. */
    val minDurationSec: Int = 30,
    /** MediaStore RELATIVE_PATHs (without trailing '/') hidden from the library, subfolders included. */
    val excludedFolders: Set<String> = emptySet(),
    val songSort: SongSort = SongSort.TITLE,
    val hiddenTabs: Set<LibraryTabId> = DEFAULT_HIDDEN_TABS,
    /** The user has agreed to send track info to lrclib.net. */
    val lyricsOnline: Boolean = false,
    /** Crossfade length between songs in seconds; 0 = off (gapless playback). */
    val crossfadeSec: Int = 0,
    val skipSilence: Boolean = false,
    /** Start playing when headphones or Bluetooth audio connect (while the player is paused). */
    val autoResume: Boolean = false,
    /** Remember a separate SoundAlive setting for each output (speaker, wired, each Bluetooth device). */
    val soundPerOutput: Boolean = false,
) {
    val visibleTabs: List<LibraryTabId> get() = LibraryTabId.entries.filterNot { it in hiddenTabs }

    fun isExcluded(folder: String) =
        excludedFolders.any { folder == it || folder.startsWith("$it/") }
}

/** Composers is off by default, as on the S4 (most files have no composer tag). */
val DEFAULT_HIDDEN_TABS = setOf(LibraryTabId.COMPOSERS)

/** App settings, stored in SharedPreferences like the rest of the app's state. */
object AppSettings {
    private const val PREFS = "settings"
    private const val K_MIN_DURATION = "min_duration_sec"
    private const val K_EXCLUDED = "excluded_folders"
    private const val K_SORT = "song_sort"
    private const val K_HIDDEN_TABS = "hidden_tabs"
    private const val K_LYRICS_ONLINE = "lyrics_online"
    private const val K_CROSSFADE = "crossfade_sec"
    private const val K_SKIP_SILENCE = "skip_silence"
    private const val K_AUTO_RESUME = "auto_resume"
    private const val K_SOUND_PER_OUTPUT = "sound_per_output"

    private lateinit var prefs: SharedPreferences
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val current: Settings get() = _settings.value

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Before settings existed, the lyrics consent lived in the "lyrics" prefs.
        val legacyConsent = context.getSharedPreferences("lyrics", Context.MODE_PRIVATE).getBoolean("web_consent", false)
        _settings.value = Settings(
            minDurationSec = prefs.getInt(K_MIN_DURATION, 30),
            excludedFolders = prefs.getStringSet(K_EXCLUDED, null)?.toSet().orEmpty(),
            songSort = runCatching { SongSort.valueOf(prefs.getString(K_SORT, null)!!) }.getOrDefault(SongSort.TITLE),
            hiddenTabs = prefs.getStringSet(K_HIDDEN_TABS, null)?.mapNotNull(LibraryTabId::fromKey)?.toSet()
                ?: DEFAULT_HIDDEN_TABS,
            lyricsOnline = prefs.getBoolean(K_LYRICS_ONLINE, legacyConsent),
            crossfadeSec = prefs.getInt(K_CROSSFADE, 0).coerceIn(0, 12),
            skipSilence = prefs.getBoolean(K_SKIP_SILENCE, false),
            autoResume = prefs.getBoolean(K_AUTO_RESUME, false),
            soundPerOutput = prefs.getBoolean(K_SOUND_PER_OUTPUT, false),
        )
    }

    fun update(block: (Settings) -> Settings) {
        _settings.update(block)
        val s = _settings.value
        prefs.edit()
            .putInt(K_MIN_DURATION, s.minDurationSec)
            .putStringSet(K_EXCLUDED, s.excludedFolders)
            .putString(K_SORT, s.songSort.name)
            .putStringSet(K_HIDDEN_TABS, s.hiddenTabs.map { it.key }.toSet())
            .putBoolean(K_LYRICS_ONLINE, s.lyricsOnline)
            .putInt(K_CROSSFADE, s.crossfadeSec)
            .putBoolean(K_SKIP_SILENCE, s.skipSilence)
            .putBoolean(K_AUTO_RESUME, s.autoResume)
            .putBoolean(K_SOUND_PER_OUTPUT, s.soundPerOutput)
            .apply()
    }
}
