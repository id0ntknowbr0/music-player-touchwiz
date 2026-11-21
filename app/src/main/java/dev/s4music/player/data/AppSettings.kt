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
    SONGS("songs"), PLAYLISTS("playlists"), ALBUMS("albums"), ARTISTS("artists"), GENRES("genres"), FOLDERS("folders");

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
    val hiddenTabs: Set<LibraryTabId> = emptySet(),
    /** The user has agreed to send track info to lrclib.net. */
    val lyricsOnline: Boolean = false,
) {
    val visibleTabs: List<LibraryTabId> get() = LibraryTabId.entries.filterNot { it in hiddenTabs }

    fun isExcluded(folder: String) =
        excludedFolders.any { folder == it || folder.startsWith("$it/") }
}

/** App settings, stored in SharedPreferences like the rest of the app's state. */
object AppSettings {
    private const val PREFS = "settings"
    private const val K_MIN_DURATION = "min_duration_sec"
    private const val K_EXCLUDED = "excluded_folders"
    private const val K_SORT = "song_sort"
    private const val K_HIDDEN_TABS = "hidden_tabs"
    private const val K_LYRICS_ONLINE = "lyrics_online"

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
            hiddenTabs = prefs.getStringSet(K_HIDDEN_TABS, null).orEmpty().mapNotNull(LibraryTabId::fromKey).toSet(),
            lyricsOnline = prefs.getBoolean(K_LYRICS_ONLINE, legacyConsent),
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
            .apply()
    }
}
