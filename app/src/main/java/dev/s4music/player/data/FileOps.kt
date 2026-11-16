package dev.s4music.player.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.ArtworkFactory
import org.jaudiotagger.tag.reference.PictureTypes
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.max
import kotlin.math.min

/** New values from the "Edit track info" dialog. */
data class TrackEdits(
    val fileName: String,   // without extension
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val year: String,
)

/**
 * Writes tags, cover art and renames files. Callers must already hold write access to the
 * song's MediaStore URI (granted by the system dialog from [MediaStore.createWriteRequest]).
 */
object FileOps {
    init {
        TagOptionSingleton.getInstance().isAndroid = true
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    fun extensionOf(song: Song) = song.displayName.substringAfterLast('.', "").lowercase()

    /** Applies [edits] (and an optional new cover); returns the song's new file path. */
    suspend fun apply(context: Context, song: Song, edits: TrackEdits, cover: ByteArray? = null): String =
        withContext(Dispatchers.IO) {
            fun norm(v: String, unknown: String) = if (v == unknown) "" else v
            val tagsChanged = edits.title != song.title ||
                edits.artist != norm(song.artist, MediaStoreRepository.UNKNOWN_ARTIST) ||
                edits.album != norm(song.album, MediaStoreRepository.UNKNOWN_ALBUM) ||
                edits.genre != norm(song.genre, MediaStoreRepository.UNKNOWN_GENRE) ||
                edits.year != (song.year.takeIf { it > 0 }?.toString() ?: "")

            if (tagsChanged || cover != null) {
                rewrite(context, song) { tag ->
                    if (tagsChanged) {
                        fun put(key: FieldKey, value: String) {
                            if (value.isBlank()) runCatching { tag.deleteField(key) } else tag.setField(key, value.trim())
                        }
                        put(FieldKey.TITLE, edits.title)
                        put(FieldKey.ARTIST, edits.artist)
                        put(FieldKey.ALBUM, edits.album)
                        put(FieldKey.GENRE, edits.genre)
                        put(FieldKey.YEAR, edits.year)
                    }
                    if (cover != null) setArtwork(context, tag, cover)
                }
            }

            var newPath = song.path
            val ext = extensionOf(song)
            val newName = edits.fileName.trim().let { if (ext.isNotEmpty()) "$it.$ext" else it }
            if (edits.fileName.isNotBlank() && newName != song.displayName) {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
                context.contentResolver.update(song.uri, values, null, null)
                newPath = File(File(song.path).parentFile, newName).path
            }
            scan(context, newPath)
            newPath
        }

    /** Embeds [cover] into the song file only (used for the rest of an album). */
    suspend fun setCover(context: Context, song: Song, cover: ByteArray) = withContext(Dispatchers.IO) {
        rewrite(context, song) { tag -> setArtwork(context, tag, cover) }
        scan(context, song.path)
    }

    /** Loads a picked image, scales it to at most 1000 px and encodes it as JPEG. */
    suspend fun prepareCover(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val w = info.size.width; val h = info.size.height
            val scale = min(1f, 1000f / max(w, h))
            decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    /** Copies the file to cache, edits its tag, and writes it back through MediaStore. */
    private fun rewrite(context: Context, song: Song, edit: (Tag) -> Unit) {
        val resolver = context.contentResolver
        val ext = extensionOf(song).ifEmpty { "mp3" }
        val tmp = File(context.cacheDir, "edit_${song.id}.$ext")
        try {
            resolver.openInputStream(song.uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            val audio = AudioFileIO.read(tmp)
            edit(audio.tagOrCreateAndSetDefault)
            audio.commit()
            // "wt" truncates, so the rewritten file fully replaces the original.
            resolver.openOutputStream(song.uri, "wt")!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
        } finally {
            tmp.delete()
        }
    }

    private fun setArtwork(context: Context, tag: Tag, cover: ByteArray) {
        val file = File(context.cacheDir, "cover_${System.nanoTime()}.jpg")
        try {
            file.writeBytes(cover)
            val art = ArtworkFactory.createArtworkFromFile(file)
            art.mimeType = "image/jpeg"
            art.pictureType = PictureTypes.DEFAULT_ID   // front cover
            runCatching { tag.deleteArtworkField() }
            tag.setField(art)
        } finally {
            file.delete()
        }
    }

    private fun scan(context: Context, path: String) {
        if (path.isNotEmpty()) MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
    }

    /** Lyrics embedded in the file's tags (USLT / LYRICS / ©lyr), if any. */
    suspend fun readEmbeddedLyrics(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.path.isEmpty()) return@withContext null
        runCatching {
            AudioFileIO.read(File(song.path)).tag?.getFirst(FieldKey.LYRICS)?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}

/**
 * Covers the user set in this app. They're also embedded in the files, but Android's
 * media thumbnails can take a while to refresh, so these copies make the change show at once.
 */
object CoverStore {
    private val _version = MutableStateFlow(0)
    /** Bumped whenever a cover changes, so images reload. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun dir(context: Context) = File(context.filesDir, "covers").apply { mkdirs() }
    private fun songFile(context: Context, id: Long) = File(dir(context), "s$id.jpg")
    private fun albumFile(context: Context, id: Long) = File(dir(context), "a$id.jpg")

    fun saveForSong(context: Context, songId: Long, bytes: ByteArray) {
        songFile(context, songId).writeBytes(bytes)
        _version.value++
    }

    fun saveForAlbum(context: Context, albumId: Long, songIds: List<Long>, bytes: ByteArray) {
        albumFile(context, albumId).writeBytes(bytes)
        songIds.forEach { songFile(context, it).delete() }   // album cover now applies to all
        _version.value++
    }

    fun find(context: Context, songId: Long?, albumId: Long?): File? =
        songId?.let { songFile(context, it) }?.takeIf { it.exists() }
            ?: albumId?.let { albumFile(context, it) }?.takeIf { it.exists() }

    fun decode(file: File, sizePx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}
