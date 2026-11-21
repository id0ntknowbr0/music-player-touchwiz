package dev.s4music.player.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Songs marked with the heart, in the order they were added. Backs the "Favourites" playlist. */
object Favourites {
    private const val KEY = "ids"
    private lateinit var prefs: SharedPreferences
    private val _ids = MutableStateFlow<List<Long>>(emptyList())
    val ids: StateFlow<List<Long>> = _ids.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("favourites", Context.MODE_PRIVATE)
        _ids.value = prefs.getString(KEY, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
    }

    fun contains(songId: Long) = songId in _ids.value

    fun toggle(songId: Long): Boolean {
        val now = !contains(songId)
        save(if (now) _ids.value + songId else _ids.value - songId)
        return now
    }

    fun removeAll(songIds: Set<Long>) {
        if (_ids.value.none { it in songIds }) return
        save(_ids.value.filterNot { it in songIds })
    }

    private fun save(list: List<Long>) {
        _ids.value = list
        prefs.edit().putString(KEY, list.joinToString(",")).apply()
    }
}
