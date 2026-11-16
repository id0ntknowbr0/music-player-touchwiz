package dev.s4music.player.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Play counts and last-played times, backing "Most played" and "Recently played". */
object PlayStats {
    data class Stat(val count: Int, val lastPlayed: Long)

    private lateinit var prefs: SharedPreferences
    private val _stats = MutableStateFlow<Map<Long, Stat>>(emptyMap())
    val stats: StateFlow<Map<Long, Stat>> = _stats.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("play_stats", Context.MODE_PRIVATE)
        _stats.value = prefs.all.mapNotNull { (k, v) ->
            val id = k.toLongOrNull() ?: return@mapNotNull null
            val parts = (v as? String)?.split(',') ?: return@mapNotNull null
            val count = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            id to Stat(count, parts.getOrNull(1)?.toLongOrNull() ?: 0L)
        }.toMap()
    }

    fun recordPlay(songId: Long) {
        val now = System.currentTimeMillis()
        val updated = Stat((_stats.value[songId]?.count ?: 0) + 1, now)
        _stats.value = _stats.value + (songId to updated)
        prefs.edit().putString(songId.toString(), "${updated.count},${updated.lastPlayed}").apply()
    }
}
