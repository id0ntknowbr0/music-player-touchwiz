package dev.s4music.player.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File

/**
 * What Music Square knows about a song beyond its genre. Filled in by [MoodWorker]:
 * the BPM tag (quick pass) and, optionally, measurements from decoding part of the audio.
 */
data class MoodFeatures(
    val tagsRead: Boolean = false,
    val bpmTag: Float? = null,
    val analysed: Boolean = false,
    /** Tempo estimated from the audio. */
    val tempo: Float? = null,
    /** Average level of the analysed section, dBFS. */
    val rmsDb: Float? = null,
    /** 0..1: how bright the sound is (zero-crossing rate), a rough stand-in for "joyful". */
    val brightness: Float? = null,
)

/** A Music Square cell: [x] 0 = calm … 4 = exciting, [y] 0 = passionate … 4 = joyful. */
data class MoodCell(val x: Int, val y: Int) {
    val index get() = y * SIZE + x

    companion object {
        const val SIZE = 5
        fun fromIndex(i: Int) = MoodCell(i % SIZE, i / SIZE)
    }
}

object MoodClassifier {
    /** Genre keyword → (energy 0 calm..1 exciting, joy 0 passionate..1 joyful). Longest match wins. */
    private val GENRES: List<Pair<String, Pair<Float, Float>>> = listOf(
        "ambient" to (0.05f to 0.5f), "new age" to (0.1f to 0.6f), "meditation" to (0.05f to 0.55f),
        "classical" to (0.2f to 0.35f), "piano" to (0.15f to 0.5f), "opera" to (0.4f to 0.2f),
        "soundtrack" to (0.35f to 0.4f), "score" to (0.35f to 0.4f), "lounge" to (0.2f to 0.6f),
        "chill" to (0.2f to 0.6f), "easy listening" to (0.2f to 0.65f), "lo-fi" to (0.2f to 0.5f),
        "jazz" to (0.35f to 0.55f), "blues" to (0.4f to 0.2f), "folk" to (0.3f to 0.55f),
        "acoustic" to (0.25f to 0.55f), "singer-songwriter" to (0.3f to 0.45f), "country" to (0.45f to 0.65f),
        "soul" to (0.45f to 0.35f), "r&b" to (0.5f to 0.4f), "rnb" to (0.5f to 0.4f), "gospel" to (0.5f to 0.7f),
        "reggae" to (0.45f to 0.8f), "ska" to (0.75f to 0.85f), "latin" to (0.7f to 0.8f), "salsa" to (0.75f to 0.85f),
        "bossa" to (0.3f to 0.7f), "samba" to (0.75f to 0.9f), "afro" to (0.7f to 0.8f),
        "pop" to (0.6f to 0.8f), "k-pop" to (0.7f to 0.85f), "j-pop" to (0.7f to 0.85f), "disco" to (0.75f to 0.9f),
        "funk" to (0.7f to 0.75f), "dance" to (0.85f to 0.85f), "house" to (0.85f to 0.7f), "edm" to (0.9f to 0.7f),
        "electro" to (0.8f to 0.6f), "electronic" to (0.7f to 0.55f), "techno" to (0.9f to 0.45f),
        "trance" to (0.85f to 0.6f), "drum" to (0.95f to 0.5f), "dnb" to (0.95f to 0.5f), "dubstep" to (0.9f to 0.35f),
        "hip hop" to (0.7f to 0.4f), "hip-hop" to (0.7f to 0.4f), "rap" to (0.7f to 0.4f), "trap" to (0.75f to 0.35f),
        "rock" to (0.75f to 0.35f), "hard rock" to (0.85f to 0.25f), "alternative" to (0.6f to 0.45f),
        "indie" to (0.55f to 0.5f), "grunge" to (0.75f to 0.2f), "emo" to (0.7f to 0.15f), "punk" to (0.9f to 0.3f),
        "metal" to (0.95f to 0.15f), "gothic" to (0.6f to 0.1f), "children" to (0.5f to 0.95f),
        "christmas" to (0.5f to 0.9f), "holiday" to (0.5f to 0.9f), "ballad" to (0.2f to 0.3f),
    ).sortedByDescending { it.first.length }

    fun genreMood(genre: String): Pair<Float, Float>? {
        val g = genre.lowercase()
        if (g.isBlank() || g == MediaStoreRepository.UNKNOWN_GENRE.lowercase()) return null
        return GENRES.firstOrNull { g.contains(it.first) }?.second
    }

    private fun norm(v: Float, lo: Float, hi: Float) = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)

    /** The song's cell, or null when nothing is known about it yet (no genre, BPM or analysis). */
    fun cell(song: Song, f: MoodFeatures?): MoodCell? {
        val genre = genreMood(song.genre)
        val tempo = f?.tempo ?: f?.bpmTag
        val tempoN = tempo?.let { norm(it, 60f, 180f) }
        val loudN = f?.rmsDb?.let { norm(it, -30f, -8f) }

        val measured = listOfNotNull(tempoN, loudN)
        val energy = when {
            measured.isNotEmpty() && genre != null -> measured.average().toFloat() * 0.6f + genre.first * 0.4f
            measured.isNotEmpty() -> measured.average().toFloat()
            genre != null -> genre.first
            else -> return null
        }
        val bright = f?.brightness
        val joy = when {
            genre != null && bright != null -> genre.second * 0.7f + bright * 0.3f
            genre != null -> genre.second
            bright != null -> bright
            else -> 0.5f
        }
        fun bucket(v: Float) = (v * MoodCell.SIZE).toInt().coerceIn(0, MoodCell.SIZE - 1)
        return MoodCell(bucket(energy), bucket(joy))
    }
}

/** Cached [MoodFeatures] per song id, in filesDir/mood.json. */
object MoodStore {
    private lateinit var file: File
    private val _features = MutableStateFlow<Map<Long, MoodFeatures>>(emptyMap())
    val features: StateFlow<Map<Long, MoodFeatures>> = _features.asStateFlow()

    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "mood.json")
        _features.value = runCatching { read() }.getOrDefault(emptyMap())
    }

    fun get(id: Long) = _features.value[id]

    fun put(updates: Map<Long, MoodFeatures>) {
        if (updates.isEmpty()) return
        _features.update { it + updates }
    }

    @Synchronized
    fun save() {
        val root = JSONObject()
        _features.value.forEach { (id, f) ->
            root.put(id.toString(), JSONObject().apply {
                put("t", f.tagsRead)
                f.bpmTag?.let { put("bpm", it.toDouble()) }
                put("a", f.analysed)
                f.tempo?.let { put("tempo", it.toDouble()) }
                f.rmsDb?.let { put("rms", it.toDouble()) }
                f.brightness?.let { put("bright", it.toDouble()) }
            })
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }

    private fun read(): Map<Long, MoodFeatures> {
        if (!file.exists()) return emptyMap()
        val root = JSONObject(file.readText())
        fun JSONObject.f(key: String) = if (has(key)) getDouble(key).toFloat() else null
        return root.keys().asSequence().mapNotNull { k ->
            val id = k.toLongOrNull() ?: return@mapNotNull null
            val o = root.getJSONObject(k)
            id to MoodFeatures(o.optBoolean("t"), o.f("bpm"), o.optBoolean("a"), o.f("tempo"), o.f("rms"), o.f("bright"))
        }.toMap()
    }
}
