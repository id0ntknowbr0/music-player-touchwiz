@file:OptIn(ExperimentalFoundationApi::class)

package dev.s4music.player.ui

import android.media.AudioManager
import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import dev.s4music.player.R
import dev.s4music.player.data.Favourites
import dev.s4music.player.data.Library
import dev.s4music.player.playback.OutputKind
import dev.s4music.player.playback.OutputRoutes
import dev.s4music.player.playback.PlaybackBridge
import dev.s4music.player.ui.theme.S4Colors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val HeaderGray = Color(0xFFA6A3A3)   // music_player_title_sub
private val TimeBlue = Color(0xFF2D9BF0)

/** Full-screen player, laid out like the TouchWiz Nature UX player. */
@Composable
fun NowPlayingScreen(ps: PlayerUiState, library: Library, onClose: () -> Unit, startWithLyrics: Boolean = false) {
    val ui = LocalUi.current
    val vm = ui.player
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val song = ps.currentSongId?.let { library.byId[it] }
    var panel by rememberSaveable { mutableStateOf(if (startWithLyrics) Panel.Lyrics else Panel.Art) }
    // Back first closes the lyrics or queue, then the player itself.
    androidx.activity.compose.BackHandler(enabled = panel != Panel.Art) { panel = Panel.Art }
    val ab by PlaybackBridge.abRepeat.collectAsState()
    val sleepAt by PlaybackBridge.sleepAtElapsed.collectAsState()
    val sleepAfterCurrent by PlaybackBridge.sleepAfterCurrent.collectAsState()
    val favourites by Favourites.ids.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Swallow touches so the library underneath doesn't receive them.
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
    ) {
        // ---- Header: [close] | artist / TITLE / album | [volume] ----
        // Three 24sp lines of text; the header grows with a large system font (up to 1.5x).
        LimitFontScale(1.5f) { Box(Modifier.fillMaxWidth().height(grownBy(75.dp, 72f))) {
            Image(painterResource(R.drawable.tw_top_bg), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(139f).fillMaxHeight().clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.close_player), tint = Color(0xFFE6E6E6), modifier = Modifier.size(34.dp))
                }
                Column(Modifier.weight(440f).padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val unknown = stringResource(R.string.unknown_tag)
                    Text(ps.artist.ifEmpty { unknown }, color = HeaderGray, fontSize = 13.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(ps.title.ifEmpty { unknown }, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, modifier = Modifier.basicMarquee())
                    Text(ps.album.ifEmpty { unknown }, color = HeaderGray, fontSize = 13.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.weight(141f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    TwButton(R.drawable.tw_volume, R.drawable.tw_volume_press, stringResource(R.string.volume), Modifier.size(58.dp)) {
                        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
                    }
                }
            }
        } }

        // ---- "1/2" counter under the blue glow ----
        Box(Modifier.fillMaxWidth().height(grownBy(44.dp, 24f)), contentAlignment = Alignment.Center) {
            // Clipped so the glow stays below the header and never tints its borders.
            Canvas(Modifier.matchParentSize().clipToBounds()) {
                scale(scaleX = 1.8f, scaleY = 1f, pivot = Offset(size.width / 2, 0f)) {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(Color(0xCC2F86D6), Color(0x552F86D6), Color.Transparent),
                            center = Offset(size.width / 2, 0f), radius = size.height * 1.4f,
                        ),
                        radius = size.height * 1.4f, center = Offset(size.width / 2, 0f),
                    )
                }
            }
            Text(if (ps.queue.isEmpty()) "" else stringResource(R.string.queue_position, ps.index + 1, ps.queue.size),
                color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.align(Alignment.CenterStart).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutputButton()
                Spacer(Modifier.width(8.dp))
                if (sleepAt != null || sleepAfterCurrent) {
                    Icon(Icons.Default.Bedtime, stringResource(R.string.music_auto_off_on), tint = TimeBlue,
                        modifier = Modifier.size(18.dp).clickable { ui.showDialog(GlobalDialog.SleepTimer) })
                    Spacer(Modifier.width(8.dp))
                }
                if (ps.speed != 1f) {
                    Text(stringResource(R.string.speed_value, ps.speed), color = TimeBlue, fontSize = 13.sp,
                        modifier = Modifier.clickable { ui.showDialog(GlobalDialog.PlaySpeed) })
                }
            }
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                if (song != null) {
                    val isFavourite = song.id in favourites
                    IconButton(onClick = { ui.toggleFavourite(song) }) {
                        Icon(
                            if (isFavourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            stringResource(if (isFavourite) R.string.remove_from_favourites else R.string.add_to_favourites),
                            tint = if (isFavourite) TimeBlue else Color(0xFFD0D0D0),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                PlayerMenu(ps, song?.let { s ->
                    listOf(
                        MenuAction(stringResource(R.string.lyrics)) { panel = Panel.Lyrics },
                        MenuAction(stringResource(R.string.add_to_playlist)) { ui.addToPlaylist(listOf(s)) },
                        MenuAction(stringResource(R.string.edit_track_info)) { ui.editSong(s) },
                        MenuAction(stringResource(R.string.set_as)) { ui.setAs(s) },
                        MenuAction(stringResource(R.string.go_to_album)) {
                            ui.closePlayer(); ui.navigate(Screen.Songs(s.album, SongSource.Album(s.albumId)))
                        },
                        MenuAction(stringResource(R.string.go_to_artist)) {
                            ui.closePlayer(); ui.navigate(Screen.Songs(s.artist, SongSource.Artist(s.artistId)))
                        },
                    )
                }.orEmpty() + listOf(
                    MenuAction(stringResource(R.string.soundalive)) { ui.showDialog(GlobalDialog.SoundAlive) },
                    MenuAction(stringResource(R.string.play_speed)) { ui.showDialog(GlobalDialog.PlaySpeed) },
                    MenuAction(stringResource(R.string.music_auto_off)) { ui.showDialog(GlobalDialog.SleepTimer) },
                ) + song?.let { s ->
                    listOf(
                        MenuAction(stringResource(R.string.details)) { ui.showDetails(s) },
                        MenuAction(stringResource(R.string.delete)) { ui.deleteSongs(listOf(s)) },
                    )
                }.orEmpty())
            }
        }

        // ---- Album art / lyrics / queue ----
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (panel) {
                Panel.Queue -> QueueList(ps)
                // Lyrics over the album art, as on the S4: the art stays visible behind a dark veil.
                Panel.Lyrics -> Box(Modifier.fillMaxSize()) {
                    AlbumArt(
                        song?.albumId, Modifier.fillMaxSize(), sizePx = 1024, shape = RoundedCornerShape(0.dp),
                        fallbackData = ps.artworkData, placeholder = R.drawable.tw_default_art_large, songId = song?.id,
                    )
                    LyricsPanel(song, ps.positionMs, onClose = { panel = Panel.Art }, background = Color(0xC0000000))
                }
                Panel.Art -> AlbumArt(
                    song?.albumId,
                    Modifier.padding(horizontal = 40.dp, vertical = 6.dp).widthIn(max = 440.dp).fillMaxWidth()
                        .aspectRatio(1f, matchHeightConstraintsFirst = true)
                        .clickable { panel = Panel.Lyrics },
                    sizePx = 1024,
                    shape = RoundedCornerShape(0.dp),
                    fallbackData = ps.artworkData,
                    placeholder = R.drawable.tw_default_art_large,
                    songId = song?.id,
                )
            }
        }

        // ---- Shuffle / repeat / A-B ----
        Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                TwButton(if (ps.shuffle) R.drawable.tw_shuffle_on else R.drawable.tw_shuffle_off, null,
                    stringResource(if (ps.shuffle) R.string.shuffle_on else R.string.shuffle_off), Modifier.size(56.dp), onClick = vm::toggleShuffle)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                val (res, desc) = when (ps.repeatMode) {
                    Player.REPEAT_MODE_ALL -> R.drawable.tw_repeat_all to R.string.repeat_all
                    Player.REPEAT_MODE_ONE -> R.drawable.tw_repeat_one to R.string.repeat_one
                    else -> R.drawable.tw_repeat_off to R.string.repeat_off
                }
                TwButton(res, null, stringResource(desc), Modifier.size(56.dp), onClick = vm::cycleRepeat)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                val res = when {
                    ab == null -> R.drawable.tw_ab_off
                    ab?.b == null -> R.drawable.tw_ab_a
                    else -> R.drawable.tw_ab_on
                }
                TwButton(res, null, stringResource(R.string.ab_repeat), Modifier.width(62.dp).height(48.dp), onClick = vm::abRepeatClick)
            }
        }

        // ---- Seek bar + times ----
        TwSeekBar(ps)

        // ---- Control bar ----
        Box(Modifier.fillMaxWidth().height(86.dp)) {
            Image(painterResource(R.drawable.tw_control_bg), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
            Row(Modifier.fillMaxSize().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(0.8f), contentAlignment = Alignment.Center) {
                    TwButton(R.drawable.tw_soundalive, R.drawable.tw_soundalive_press, stringResource(R.string.soundalive), Modifier.size(52.dp)) {
                        ui.showDialog(GlobalDialog.SoundAlive)
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    HoldButton(R.drawable.tw_prev, R.drawable.tw_prev_press, stringResource(R.string.previous), 70.dp,
                        onTap = vm::previous, onHold = { vm.seekBy(-3000) })
                }
                Box(Modifier.weight(1.3f), contentAlignment = Alignment.Center) {
                    TwButton(
                        if (ps.isPlaying) R.drawable.tw_pause else R.drawable.tw_play,
                        if (ps.isPlaying) R.drawable.tw_pause_press else R.drawable.tw_play_press,
                        stringResource(if (ps.isPlaying) R.string.pause else R.string.play),
                        Modifier.width(98.dp).height(87.dp),
                        onClick = vm::togglePlay,
                    )
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    HoldButton(R.drawable.tw_next, R.drawable.tw_next_press, stringResource(R.string.next), 70.dp,
                        onTap = vm::next, onHold = { vm.seekBy(3000) })
                }
                Box(Modifier.weight(0.8f), contentAlignment = Alignment.Center) {
                    val showingQueue = panel == Panel.Queue
                    TwButton(
                        if (showingQueue) R.drawable.tw_nowplaying else R.drawable.tw_list,
                        if (showingQueue) R.drawable.tw_nowplaying_press else R.drawable.tw_list_press,
                        stringResource(if (showingQueue) R.string.now_playing else R.string.list),
                        Modifier.size(52.dp),
                    ) { panel = if (showingQueue) Panel.Art else Panel.Queue }
                }
            }
        }
    }
}

private enum class Panel { Art, Lyrics, Queue }

/** Shows where music is playing; tap to open Android's output switcher (Bluetooth, wired, Cast). */
@Composable
private fun OutputButton() {
    val context = LocalContext.current
    val route by OutputRoutes.current.collectAsState()
    val (icon, label) = when (route.kind) {
        OutputKind.SPEAKER -> Icons.Default.PhoneAndroid to stringResource(R.string.output_speaker)
        OutputKind.WIRED -> Icons.Default.Headphones to stringResource(R.string.output_wired)
        OutputKind.BLUETOOTH -> Icons.Default.BluetoothAudio to (route.name ?: stringResource(R.string.output_bluetooth))
        OutputKind.USB -> Icons.Default.Usb to (route.name ?: stringResource(R.string.output_usb))
    }
    Row(
        Modifier.clickable { OutputRoutes.openSystemOutputSwitcher(context) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, stringResource(R.string.output_choose, label), tint = if (route.kind == OutputKind.SPEAKER)
            Color(0xFFD0D0D0) else TimeBlue, modifier = Modifier.size(18.dp))
        if (route.kind == OutputKind.BLUETOOTH || route.kind == OutputKind.USB) {
            Spacer(Modifier.width(4.dp))
            Text(label, color = TimeBlue, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 96.dp))
        }
    }
}

@Composable
private fun PlayerMenu(ps: PlayerUiState, actions: List<MenuAction>) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, enabled = ps.hasMedia) {
        Icon(Icons.Default.MoreVert, stringResource(R.string.more_options), tint = Color(0xFFD0D0D0))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        actions.forEach { a -> DropdownMenuItem(text = { Text(a.label) }, onClick = { open = false; a.onClick() }) }
    }
}

/** Image button using the original normal/pressed bitmaps. */
@Composable
fun TwButton(
    @DrawableRes normal: Int,
    @DrawableRes pressed: Int?,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val haptics = rememberHaptics()
    Image(
        painterResource(if (isPressed && pressed != null) pressed else normal),
        contentDescription = description,
        modifier = modifier.clickable(interaction, indication = null) { haptics.tap(); onClick() },
        contentScale = ContentScale.Fit,
        alpha = if (isPressed && pressed == null) 0.6f else 1f,
    )
}

/** Tap = skip; press and hold = fast scrub (the TouchWiz hi-speed FF/REW). */
@Composable
private fun HoldButton(
    @DrawableRes normal: Int,
    @DrawableRes pressed: Int,
    description: String,
    size: Dp,
    onTap: () -> Unit,
    onHold: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    var down by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    Image(
        painterResource(if (down) pressed else normal),
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(size).pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown()
                down = true
                var held = false
                val job = scope.launch {
                    delay(450)
                    held = true
                    while (isActive) { haptics.tick(); hold(); delay(150) }
                }
                val up = waitForUpOrCancellation()
                job.cancel()
                down = false
                if (!held && up != null) { haptics.tap(); tap() }
            }
        },
    )
}

/** Thin blue progress line with the original blue dot thumb, times underneath. */
@Composable
private fun TwSeekBar(ps: PlayerUiState) {
    val vm = LocalUi.current.player
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val dur = ps.durationMs.coerceAtLeast(1)
    val fraction = dragFraction ?: (ps.positionMs.toFloat() / dur).coerceIn(0f, 1f)
    val ab by PlaybackBridge.abRepeat.collectAsState()
    val haptics = rememberHaptics()

    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(30.dp).pointerInput(ps.durationMs) {
                if (ps.durationMs <= 0) return@pointerInput
                awaitEachGesture {
                    val first = awaitFirstDown()
                    fun frac(x: Float) = (x / size.width).coerceIn(0f, 1f)
                    dragFraction = frac(first.position.x)
                    haptics.tick()
                    // A tick for every 5% of the song while scrubbing.
                    var lastStep = (dragFraction!! * 20).toInt()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        val f = frac(change.position.x)
                        dragFraction = f
                        val step = (f * 20).toInt()
                        if (step != lastStep) { lastStep = step; haptics.tick() }
                        change.consume()
                    }
                    dragFraction?.let { vm.seekTo((it * dur).toLong()) }
                    dragFraction = null
                }
            },
            contentAlignment = Alignment.CenterStart,
        ) {
            val width = maxWidth
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                val y = size.height / 2
                drawLine(Color(0xFF3C3C3C), Offset(0f, y), Offset(size.width, y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                drawLine(
                    Brush.horizontalGradient(listOf(Color(0xFF1565C0), Color(0xFF39A9FF))),
                    Offset(0f, y), Offset(size.width * fraction, y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
                )
            }
            Image(
                painterResource(R.drawable.tw_seek_thumb), null,
                Modifier.size(40.dp).offset(x = width * fraction - 20.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(dragFraction?.let { (it * dur).toLong() } ?: ps.positionMs), color = TimeBlue, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            ab?.let { r ->
                Text(
                    if (r.b == null) stringResource(R.string.ab_point_a, formatTime(r.a))
                    else stringResource(R.string.ab_range, formatTime(r.a), formatTime(r.b)),
                    color = S4Colors.Text2, fontSize = 12.sp,
                )
                Spacer(Modifier.weight(1f))
            }
            Text(formatTime(ps.durationMs), color = Color(0xFFB4B4B4), fontSize = 16.sp)
        }
    }
}

@Composable
private fun QueueList(ps: PlayerUiState) {
    val vm = LocalUi.current.player
    val listState = rememberLazyListState((ps.index - 2).coerceAtLeast(0))
    LazyColumn(Modifier.fillMaxSize().background(Color(0xFF111315)), state = listState) {
        itemsIndexed(ps.queue, key = { i, q -> "${q.mediaId}#$i" }) { i, q ->
            val current = i == ps.index
            Row(
                Modifier.fillMaxWidth().clickable { vm.playQueueIndex(i) }.padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(32.dp)) {
                    if (current) Icon(Icons.Default.PlayArrow, null, tint = TimeBlue, modifier = Modifier.size(20.dp))
                    else Text("${i + 1}", color = S4Colors.Text2, fontSize = 13.sp)
                }
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(q.title, color = if (current) S4Colors.Accent else S4Colors.Text1, fontSize = 15.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(q.artist, color = S4Colors.Text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { vm.removeQueueIndex(i) }) {
                    Icon(Icons.Default.Close, stringResource(R.string.remove_from_queue), tint = S4Colors.Text2, modifier = Modifier.size(18.dp))
                }
            }
            HorizontalDivider(color = S4Colors.Divider, thickness = 0.5.dp)
        }
    }
}

// ---------- Mini player (S4 library bottom bar) ----------

@Composable
fun MiniPlayer(ps: PlayerUiState, library: Library, onOpen: () -> Unit) = LimitFontScale(1.5f) {
    val vm = LocalUi.current.player
    val song = ps.currentSongId?.let { library.byId[it] }
    val fraction = if (ps.durationMs > 0) (ps.positionMs.toFloat() / ps.durationMs).coerceIn(0f, 1f) else 0f
    Row(
        // Two 24sp lines of text above the buttons; taller with a large system font.
        Modifier.fillMaxWidth().height(grownBy(108.dp, 48f))
            .background(Brush.verticalGradient(listOf(Color(0xFF3D4045), Color(0xFF54575C))))
    ) {
        AlbumArt(
            song?.albumId,
            Modifier.fillMaxHeight().aspectRatio(1f).clickable(onClick = onOpen),
            sizePx = 300, shape = RoundedCornerShape(0.dp), fallbackData = ps.artworkData, songId = song?.id,
        )
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 10.dp, end = 10.dp, top = 6.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.tw_mini_note), null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (ps.artist.isEmpty()) ps.title else "${ps.title} - ${ps.artist}",
                    color = Color(0xFFF0F0F0), fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f).basicMarquee(),
                )
            }
            Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                val y = size.height / 2
                drawLine(Color(0xFF26282B), Offset(0f, y), Offset(size.width, y), strokeWidth = 3.dp.toPx())
                drawLine(TimeBlue, Offset(0f, y), Offset(size.width * fraction, y), strokeWidth = 3.dp.toPx())
                drawCircle(TimeBlue, radius = 4.dp.toPx(), center = Offset(size.width * fraction, y))
            }
            Row(Modifier.fillMaxWidth()) {
                Text(formatTime(ps.positionMs), color = TimeBlue, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(formatTime(ps.durationMs), color = Color(0xFFF0F0F0), fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    TwButton(R.drawable.tw_w_prev, R.drawable.tw_w_prev_press, stringResource(R.string.previous), Modifier.size(54.dp), onClick = vm::previous)
                }
                Box(Modifier.weight(1.2f), contentAlignment = Alignment.Center) {
                    TwButton(
                        if (ps.isPlaying) R.drawable.tw_w_pause else R.drawable.tw_w_play,
                        if (ps.isPlaying) R.drawable.tw_w_pause_press else R.drawable.tw_w_play_press,
                        stringResource(if (ps.isPlaying) R.string.pause else R.string.play),
                        Modifier.width(72.dp).height(64.dp), onClick = vm::togglePlay,
                    )
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    TwButton(R.drawable.tw_w_next, R.drawable.tw_w_next_press, stringResource(R.string.next), Modifier.size(54.dp), onClick = vm::next)
                }
            }
        }
    }
}

/** Remaining time until "Music auto off" fires, ticking every second; null when off. */
@Composable
fun rememberSleepRemaining(): Long? {
    val at by PlaybackBridge.sleepAtElapsed.collectAsState()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(at) {
        while (at != null) { now = SystemClock.elapsedRealtime(); delay(1000) }
    }
    return at?.let { (it - now).coerceAtLeast(0) }
}
