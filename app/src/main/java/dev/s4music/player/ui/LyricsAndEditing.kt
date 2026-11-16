package dev.s4music.player.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.s4music.player.data.FileOps
import dev.s4music.player.data.Lyrics
import dev.s4music.player.data.LyricsRepository
import dev.s4music.player.data.Song
import dev.s4music.player.data.TrackEdits
import dev.s4music.player.ui.theme.S4Colors
import kotlinx.coroutines.launch

private val LyricBg = Color(0xFF1B1B1B)     // music_player_lyric_text_background
private val LyricText = Color(0xFFE6E6E6)   // music_player_lyric_text_color
private val LyricActive = Color(0xFF2D9BF0)

private sealed interface LyricsState {
    data object Loading : LyricsState
    data object None : LyricsState
    data class Loaded(val lyrics: Lyrics) : LyricsState
}

/** Lyrics view shown over the album art. Tap empty space to go back to the art. */
@Composable
fun LyricsPanel(song: Song?, positionMs: Long, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var askConsent by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(LyricsRepository.hasWebConsent(context)) }

    val state by produceState<LyricsState>(LyricsState.Loading, song?.id, version) {
        value = LyricsState.Loading
        value = song?.let { LyricsRepository.load(context, it) }?.let { LyricsState.Loaded(it) } ?: LyricsState.None
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && song != null) scope.launch {
            val ok = runCatching { LyricsRepository.importFrom(context, song.id, uri) }.getOrDefault(false)
            Toast.makeText(context, if (ok) "Lyrics imported" else "Couldn't read that file (use .lrc or .txt)",
                Toast.LENGTH_SHORT).show()
            version++
        }
    }

    fun searchOnline() {
        val s = song ?: return
        if (!consent) { askConsent = true; return }
        scope.launch {
            busy = true
            val msg = when (val r = LyricsRepository.fetchFromWeb(context, s)) {
                LyricsRepository.WebResult.Found -> "Lyrics found"
                LyricsRepository.WebResult.NotFound -> "No lyrics found online for this track"
                is LyricsRepository.WebResult.Failed -> "Couldn't search: ${r.message}"
            }
            busy = false
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            version++
        }
    }

    Column(
        Modifier.fillMaxSize().background(LyricBg)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose)
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (val st = state) {
                LyricsState.Loading -> CircularProgressIndicator(color = S4Colors.Accent)
                LyricsState.None -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No lyrics", color = LyricText, fontSize = 18.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Import a .lrc or .txt file, or search online.", color = S4Colors.Text2, fontSize = 13.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                }
                is LyricsState.Loaded -> {
                    val lyrics = st.lyrics
                    if (lyrics.synced) {
                        val current = lyrics.currentIndex(positionMs)
                        val listState = rememberLazyListState()
                        LaunchedEffect(current) {
                            if (current >= 0) listState.animateScrollToItem((current - 3).coerceAtLeast(0))
                        }
                        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = 24.dp)) {
                            itemsIndexed(lyrics.lines) { i, line ->
                                Text(
                                    line.text.ifEmpty { "♪" },
                                    color = if (i == current) LyricActive else LyricText.copy(alpha = 0.75f),
                                    fontSize = if (i == current) 19.sp else 17.sp,
                                    fontWeight = if (i == current) FontWeight.Bold else FontWeight.Normal,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                                )
                            }
                        }
                    } else {
                        Text(
                            lyrics.lines.joinToString("\n") { it.text },
                            color = LyricText, fontSize = 16.sp, textAlign = TextAlign.Center, lineHeight = 26.sp,
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                        )
                    }
                }
            }
            if (busy) CircularProgressIndicator(color = S4Colors.Accent, modifier = Modifier.size(36.dp))
        }

        // Actions
        Row(Modifier.fillMaxWidth().background(Color(0xFF26292C)).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { importer.launch(arrayOf("*/*")) }, enabled = song != null) {
                Text("Import", color = S4Colors.Accent)
            }
            TextButton(onClick = ::searchOnline, enabled = song != null && !busy) {
                Text("Search online", color = S4Colors.Accent)
            }
            val loaded = state as? LyricsState.Loaded
            if (loaded?.lyrics?.source == Lyrics.Source.Saved && song != null) {
                TextButton(onClick = { scope.launch { LyricsRepository.delete(context, song.id); version++ } }) {
                    Text("Remove", color = S4Colors.Text2)
                }
            } else if (consent) {
                TextButton(onClick = {
                    LyricsRepository.setWebConsent(context, false); consent = false
                    Toast.makeText(context, "Online lyrics turned off", Toast.LENGTH_SHORT).show()
                }) { Text("Online: on", color = S4Colors.Text2, fontSize = 12.sp) }
            }
        }
    }

    if (askConsent) {
        AlertDialog(
            onDismissRequest = { askConsent = false },
            title = { Text("Search lyrics online?") },
            text = {
                Text(
                    "To find lyrics, Music Player will send this track's title, artist, album and length to " +
                        "lrclib.net, a free lyrics service. Nothing else is sent, and nothing is sent until you " +
                        "tap \"Search online\".\n\nYou can turn this off again from the lyrics screen."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    LyricsRepository.setWebConsent(context, true); consent = true; askConsent = false
                    searchOnline()
                }) { Text("Allow") }
            },
            dismissButton = { TextButton(onClick = { askConsent = false }) { Text("Not now") } },
        )
    }
}

/** "Edit track info": cover art, file name and the main tags. */
@Composable
fun EditTrackDialog(
    song: Song,
    albumSongCount: Int,
    onDismiss: () -> Unit,
    onSave: (edits: TrackEdits, cover: android.net.Uri?, wholeAlbum: Boolean) -> Unit,
) {
    val ext = FileOps.extensionOf(song)
    var fileName by remember { mutableStateOf(song.displayName.removeSuffix(".$ext").ifEmpty { song.displayName }) }
    var title by remember { mutableStateOf(song.title) }
    var artist by remember { mutableStateOf(song.artist.takeUnless { it == "Unknown artist" } ?: "") }
    var album by remember { mutableStateOf(song.album.takeUnless { it == "Unknown album" } ?: "") }
    var genre by remember { mutableStateOf(song.genre.takeUnless { it == "Unknown genre" } ?: "") }
    var year by remember { mutableStateOf(song.year.takeIf { it > 0 }?.toString() ?: "") }
    var cover by remember { mutableStateOf<android.net.Uri?>(null) }
    var wholeAlbum by remember { mutableStateOf(true) }
    val invalidName = fileName.isBlank() || fileName.any { it in "/\\:*?\"<>|" }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) cover = uri
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit track info") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // Cover art
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(84.dp)) {
                        val picked = cover
                        if (picked != null) PickedImage(picked, Modifier.fillMaxSize())
                        else AlbumArt(song.albumId, Modifier.fillMaxSize(), sizePx = 256, songId = song.id)
                    }
                    Spacer(Modifier.size(12.dp))
                    Column {
                        TextButton(onClick = {
                            picker.launch(androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) { Text(if (cover == null) "Change cover" else "Choose another", color = S4Colors.Accent) }
                        if (cover != null && albumSongCount > 1) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { wholeAlbum = !wholeAlbum }) {
                                androidx.compose.material3.Checkbox(wholeAlbum, { wholeAlbum = it })
                                Text("Use for whole album ($albumSongCount songs)", fontSize = 13.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(fileName, { fileName = it }, label = { Text("File name") }, singleLine = true,
                    isError = invalidName, suffix = { if (ext.isNotEmpty()) Text(".$ext") })
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                OutlinedTextField(artist, { artist = it }, label = { Text("Artist") }, singleLine = true)
                OutlinedTextField(album, { album = it }, label = { Text("Album") }, singleLine = true)
                OutlinedTextField(genre, { genre = it }, label = { Text("Genre") }, singleLine = true)
                OutlinedTextField(year, { v -> year = v.filter(Char::isDigit).take(4) }, label = { Text("Year") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Spacer(Modifier.height(6.dp))
                Text("Android will ask you to allow Music Player to modify this file.",
                    color = S4Colors.Text2, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !invalidName && title.isNotBlank(),
                onClick = {
                    onSave(
                        TrackEdits(fileName.trim(), title.trim(), artist.trim(), album.trim(), genre.trim(), year.trim()),
                        cover, wholeAlbum && albumSongCount > 1,
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Preview of an image the user just picked. */
@Composable
private fun PickedImage(uri: android.net.Uri, modifier: Modifier) {
    val context = LocalContext.current
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, uri) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                android.graphics.ImageDecoder.decodeBitmap(
                    android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
                ) { d, _, _ -> d.setTargetSize(256, 256); d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE }
                    .asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.background(Color(0xFF1E2A35))) {
        bmp?.let {
            androidx.compose.foundation.Image(it, "New cover", Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        }
    }
}
