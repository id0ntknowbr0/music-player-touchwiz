package dev.s4music.player.ui

import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalView
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import dev.s4music.player.data.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.s4music.player.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.s4music.player.data.Song
import dev.s4music.player.ui.theme.S4Colors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------- Haptics ----------

/** TouchWiz-style touch vibration. Off when the app setting or Android's own touch feedback is off. */
class Haptics(private val view: View) {
    private fun perform(type: Int) {
        if (AppSettings.current.haptics) view.performHapticFeedback(type)
    }

    /** Buttons. */
    fun tap() = perform(HapticFeedbackConstants.VIRTUAL_KEY)

    /** Long-press menus. */
    fun longPress() = perform(HapticFeedbackConstants.LONG_PRESS)

    /** Small steps: seeking, index letters, Music Square cells. */
    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK)
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

// ---------- Album art ----------

private object ArtCache {
    private val NONE = Any()
    private val cache = LruCache<String, Any>(160)

    private fun key(songId: Long?, albumId: Long?, size: Int) =
        "$songId/$albumId@$size#${dev.s4music.player.data.CoverStore.version.value}"

    fun peek(songId: Long?, albumId: Long?, size: Int): ImageBitmap? = cache.get(key(songId, albumId, size)) as? ImageBitmap

    suspend fun load(context: Context, songId: Long?, albumId: Long?, size: Int): ImageBitmap? {
        val k = key(songId, albumId, size)
        cache.get(k)?.let { return it as? ImageBitmap }
        val bmp = withContext(Dispatchers.IO) {
            // 1) cover set in this app, 2) Android's album thumbnail (embedded art)
            dev.s4music.player.data.CoverStore.find(context, songId, albumId)
                ?.let { dev.s4music.player.data.CoverStore.decode(it, size)?.asImageBitmap() }
                ?: albumId?.let {
                    runCatching {
                        val uri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, it)
                        context.contentResolver.loadThumbnail(uri, Size(size, size), null).asImageBitmap()
                    }.getOrNull()
                }
        }
        cache.put(k, bmp ?: NONE)
        return bmp
    }
}

@Composable
fun AlbumArt(
    albumId: Long?,
    modifier: Modifier = Modifier,
    sizePx: Int = 160,
    shape: Shape = RoundedCornerShape(3.dp),
    fallbackData: ByteArray? = null,
    @androidx.annotation.DrawableRes placeholder: Int = dev.s4music.player.R.drawable.tw_default_art,
    songId: Long? = null,
) {
    val context = LocalContext.current
    val coverVersion by dev.s4music.player.data.CoverStore.version.collectAsState()
    val fromStore by produceState(ArtCache.peek(songId, albumId, sizePx), songId, albumId, sizePx, coverVersion) {
        value = if (songId == null && albumId == null) null else ArtCache.load(context, songId, albumId, sizePx)
    }
    val fromData = remember(fallbackData) {
        fallbackData?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
    }
    val bmp = fromStore ?: fromData
    Box(modifier.clip(shape).background(S4Colors.ArtPlaceholder), contentAlignment = Alignment.Center) {
        if (bmp != null) {
            Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Image(androidx.compose.ui.res.painterResource(placeholder), null, Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop)
        }
    }
}

// ---------- Header bar ----------

@Composable
fun HeaderBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(S4Colors.HeaderGradient),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = S4Colors.Text1)
            }
        } else Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = S4Colors.PlayerTitle, fontSize = 19.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = S4Colors.Text2, fontSize = 12.sp, maxLines = 1)
        }
        actions()
    }
}

// ---------- Menus ----------

data class MenuAction(val label: String, val onClick: () -> Unit)

@Composable
fun OverflowMenu(actions: List<MenuAction>, tint: androidx.compose.ui.graphics.Color = S4Colors.Text2) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more_options), tint = tint) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { a ->
                DropdownMenuItem(text = { Text(a.label) }, onClick = { open = false; a.onClick() })
            }
        }
    }
}

// ---------- Rows (TouchWiz list style) ----------

private val RowTitle = androidx.compose.ui.graphics.Color(0xFFF0F0F0)
private val RowSub = androidx.compose.ui.graphics.Color(0xFFA3B0BB)
private val RowDivider = androidx.compose.ui.graphics.Color(0xFF383838)
val NowPlayingBlue = androidx.compose.ui.graphics.Color(0xFF3FA9F5)

/** "Shuffle" row at the top of song lists, with optional actions on the right. */
@Composable
fun ShuffleHeader(onShuffle: (() -> Unit)?, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).background(androidx.compose.ui.graphics.Color.Black),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onShuffle != null) {
            Row(
                Modifier.fillMaxHeight().clickable(onClick = onShuffle).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.shuffle), color = RowTitle, fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Image(androidx.compose.ui.res.painterResource(dev.s4music.player.R.drawable.tw_list_shuffle), null,
                    Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        trailing()
    }
    HorizontalDivider(color = RowDivider, thickness = 1.dp)
}

/** "7 songs" footer at the end of a list. */
@Composable
fun CountFooter(text: String) {
    Box(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color(0xFF141414)).padding(vertical = 10.dp),
        contentAlignment = Alignment.Center) {
        Text(text, color = S4Colors.Text2, fontSize = 14.sp)
    }
}

/** Animated equaliser bars shown next to the song that's playing. */
@Composable
fun PlayingIndicator(modifier: Modifier = Modifier) {
    val playing = LocalUi.current.player.state.collectAsState().value.isPlaying
    if (!playing) return   // only shown while music is actually playing
    var frame by remember { mutableStateOf(0) }
    LaunchedEffect(playing) {
        while (playing) { kotlinx.coroutines.delay(260); frame = (frame + 1) % 3 }
    }
    val res = when (frame) {
        0 -> dev.s4music.player.R.drawable.tw_playing_ani_1
        1 -> dev.s4music.player.R.drawable.tw_playing_ani_2
        else -> dev.s4music.player.R.drawable.tw_playing_ani_3
    }
    Image(androidx.compose.ui.res.painterResource(res), stringResource(R.string.now_playing), modifier.size(24.dp))
}

/** Long-press context menu, styled like the TouchWiz list popup (title + options). */
@Composable
fun ContextMenuDialog(title: String, actions: List<MenuAction>, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(androidx.compose.ui.graphics.Color(0xFF2B2D30))) {
            Text(title, color = NowPlayingBlue, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp))
            HorizontalDivider(color = NowPlayingBlue, thickness = 2.dp)
            actions.forEach { a ->
                Text(a.label, color = RowTitle, fontSize = 17.sp,
                    modifier = Modifier.fillMaxWidth().clickable { onDismiss(); a.onClick() }
                        .padding(horizontal = 20.dp, vertical = 14.dp))
                HorizontalDivider(color = androidx.compose.ui.graphics.Color(0xFF3C3F43), thickness = 1.dp)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SongRow(song: Song, isCurrent: Boolean, menu: List<MenuAction>, onClick: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    Row(
        Modifier.fillMaxWidth().height(76.dp)
            .combinedClickable(onClick = onClick,
                onLongClick = { haptics.longPress(); menuOpen = true })
            .padding(start = 4.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(song.albumId, Modifier.size(66.dp), sizePx = 200, shape = androidx.compose.ui.graphics.RectangleShape,
            songId = song.id)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, color = if (isCurrent) NowPlayingBlue else RowTitle, fontSize = 19.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, color = RowSub, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (isCurrent) PlayingIndicator(Modifier.padding(start = 6.dp))
    }
    HorizontalDivider(color = RowDivider, thickness = 1.dp)
    if (menuOpen) ContextMenuDialog(song.title, menu) { menuOpen = false }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GroupRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    menu: List<MenuAction> = emptyList(),
    artAlbumId: Long? = null,
    icon: ImageVector? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    Row(
        Modifier.fillMaxWidth().height(76.dp)
            .combinedClickable(onClick = onClick,
                onLongClick = { if (menu.isNotEmpty()) { haptics.longPress(); menuOpen = true } })
            .padding(start = 4.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(66.dp).background(androidx.compose.ui.graphics.Color(0xFF1E2A35)),
                contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = S4Colors.Accent, modifier = Modifier.size(32.dp))
            }
        } else AlbumArt(artAlbumId, Modifier.size(66.dp), sizePx = 200, shape = androidx.compose.ui.graphics.RectangleShape)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = RowTitle, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, color = RowSub, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    HorizontalDivider(color = RowDivider, thickness = 1.dp)
    if (menuOpen) ContextMenuDialog(title, menu) { menuOpen = false }
}

@Composable
fun EmptyMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = S4Colors.Text2, fontSize = 16.sp)
    }
}

// ---------- Alphabetical index bar (# A B C ... Z) ----------

const val INDEX_LETTERS = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ"

/** Section letter for a title: A-Z (accents stripped), otherwise '#'. */
fun indexKey(name: String): Char {
    val first = name.trimStart().firstOrNull() ?: return '#'
    val plain = java.text.Normalizer.normalize(first.toString(), java.text.Normalizer.Form.NFD)
        .firstOrNull()?.uppercaseChar() ?: return '#'
    return if (plain in 'A'..'Z') plain else '#'
}

/** First list position at or after [letter]; falls back to the end of the list. */
fun firstIndexFor(letter: Char, keys: List<Char>): Int? {
    if (keys.isEmpty()) return null
    val target = INDEX_LETTERS.indexOf(letter)
    val i = keys.indexOfFirst { INDEX_LETTERS.indexOf(it) >= target }
    return if (i >= 0) i else keys.lastIndex
}

/**
 * Wraps a list with the TouchWiz index strip on the right. Tapping or dragging over a
 * letter jumps to the first item starting with it and shows the letter in a bubble.
 * When [firstVisible] is given, the bubble also shows the current letter while the list is
 * flung quickly, like TouchWiz's fast scroll.
 */
@Composable
fun AlphabetIndexed(
    keys: List<Char>,
    scrollTo: suspend (Int) -> Unit,
    firstVisible: (() -> Int)? = null,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var active by remember { mutableStateOf<Char?>(null) }
    var flingLetter by remember { mutableStateOf<Char?>(null) }
    val currentKeys by rememberUpdatedState(keys)
    val currentScroll by rememberUpdatedState(scrollTo)

    if (firstVisible != null && keys.size >= FAST_SCROLL_MIN_ITEMS) {
        val visible by rememberUpdatedState(firstVisible)
        LaunchedEffect(Unit) {
            var lastIndex = -1
            var lastTime = 0L
            snapshotFlow { visible() }.collectLatest { index ->
                val now = SystemClock.uptimeMillis()
                val itemsPerSec = if (lastIndex < 0) 0f
                else abs(index - lastIndex) * 1000f / (now - lastTime).coerceAtLeast(1)
                lastIndex = index; lastTime = now
                // Appears on a fast fling, then follows the list until it has been still for a moment.
                if (itemsPerSec > FAST_SCROLL_ITEMS_PER_SEC || flingLetter != null) {
                    flingLetter = currentKeys.getOrNull(index.coerceIn(0, currentKeys.lastIndex))
                }
                delay(700)
                flingLetter = null
            }
        }
    }

    val shown = active ?: flingLetter
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            content()
            shown?.let { letter ->
                Box(Modifier.align(Alignment.Center).size(84.dp).clip(RoundedCornerShape(6.dp))
                    .background(androidx.compose.ui.graphics.Color(0xDD1E2A35)), contentAlignment = Alignment.Center) {
                    Text(letter.toString(), color = androidx.compose.ui.graphics.Color.White, fontSize = 44.sp)
                }
            }
        }
        Column(
            Modifier.fillMaxHeight().width(26.dp).background(androidx.compose.ui.graphics.Color(0xFF27282B))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        var last: Char? = null
                        fun pick(y: Float) {
                            val i = (y / size.height * INDEX_LETTERS.length).toInt().coerceIn(0, INDEX_LETTERS.lastIndex)
                            val letter = INDEX_LETTERS[i]
                            if (letter != last) {
                                last = letter
                                active = letter
                                haptics.tick()
                                firstIndexFor(letter, currentKeys)?.let { scope.launch { currentScroll(it) } }
                            }
                        }
                        val down = awaitFirstDown()
                        pick(down.position.y); down.consume()
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            pick(change.position.y); change.consume()
                        }
                        active = null
                    }
                },
        ) {
            INDEX_LETTERS.forEach { c ->
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(c.toString(), color = if (c == shown) NowPlayingBlue else androidx.compose.ui.graphics.Color(0xFF8A8F96),
                        fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}

/** The fast-scroll bubble only appears on lists at least this long… */
private const val FAST_SCROLL_MIN_ITEMS = 40
/** …when they move faster than this. */
private const val FAST_SCROLL_ITEMS_PER_SEC = 25f

// ---------- Formatting ----------

/** S4 style "03:45" / "1:02:03". */
fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
