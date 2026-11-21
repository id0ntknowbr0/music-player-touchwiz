@file:OptIn(ExperimentalFoundationApi::class)

package dev.s4music.player.ui

import androidx.annotation.DrawableRes
import androidx.annotation.PluralsRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.s4music.player.R
import dev.s4music.player.data.AppSettings
import dev.s4music.player.data.Favourites
import dev.s4music.player.data.LibraryTabId
import dev.s4music.player.data.SongSort
import dev.s4music.player.data.Library
import dev.s4music.player.data.Playlist
import dev.s4music.player.data.PlaylistStore
import dev.s4music.player.data.Song
import dev.s4music.player.data.SongGroup
import dev.s4music.player.ui.theme.S4Colors

/** The original TouchWiz tab bitmap, or null for tabs drawn with a vector icon ([vectorIcon]). */
@DrawableRes
private fun LibraryTabId.icon(): Int? = when (this) {
    LibraryTabId.SONGS -> R.drawable.tw_tab_songs
    LibraryTabId.PLAYLISTS -> R.drawable.tw_tab_playlists
    LibraryTabId.ALBUMS -> R.drawable.tw_tab_albums
    LibraryTabId.ARTISTS -> R.drawable.tw_tab_artists
    LibraryTabId.GENRES -> R.drawable.tw_tab_genres
    LibraryTabId.FOLDERS -> R.drawable.tw_tab_folders
    LibraryTabId.COMPOSERS, LibraryTabId.MUSIC_SQUARE -> null
}

private fun LibraryTabId.vectorIcon() = when (this) {
    LibraryTabId.MUSIC_SQUARE -> Icons.Default.GridView
    else -> Icons.Default.Piano
}

private val TabSelected = Brush.verticalGradient(listOf(Color(0xFF4F8DBC), Color(0xFF3E76A1)))
private val TabNormal = Brush.verticalGradient(listOf(Color(0xFF27425A), Color(0xFF1C3549)))
private val TabBorder = Color(0xFF02080D)   // near-black separators, as on TouchWiz
private val TabBorderWidth = 2.dp

@Composable
fun songsLabel(n: Int) = pluralStringResource(R.plurals.songs_count, n, n)

/** Section letter source for the A-Z index when songs are in [this] order; null = no index. */
fun SongSort.indexKey(): ((Song) -> String)? = when (this) {
    SongSort.TITLE -> { s -> s.title }
    SongSort.ARTIST -> { s -> s.artist }
    SongSort.ALBUM -> { s -> s.album }
    SongSort.DATE_ADDED -> null
}

@Composable
fun LibraryScreen(
    library: Library,
    loading: Boolean,
    playlists: List<Playlist>,
    currentId: Long?,
    initialTab: LibraryTabId = LibraryTabId.SONGS,
) {
    val ui = LocalUi.current
    val settings by AppSettings.settings.collectAsStateWithLifecycle()
    val tabs = settings.visibleTabs
    var tabKey by rememberSaveable { mutableStateOf(initialTab.key) }
    val tab = LibraryTabId.fromKey(tabKey)?.takeIf { it in tabs } ?: tabs.first()

    // Search + menu at the right of the "Shuffle" row (the S4 used its hardware menu key).
    val actions: @Composable RowScope.() -> Unit = {
        IconButton(onClick = { ui.navigate(Screen.Search) }) {
            Icon(Icons.Default.Search, stringResource(R.string.search), tint = Color(0xFFD0D0D0))
        }
        val playlistItems = if (tab == LibraryTabId.PLAYLISTS) listOf(
            MenuAction(stringResource(R.string.import_playlists)) { ui.importPlaylists() },
            MenuAction(stringResource(R.string.export_all_playlists)) { ui.exportAllPlaylists() },
        ) else emptyList()
        OverflowMenu(
            listOf(
                MenuAction(stringResource(R.string.search)) { ui.navigate(Screen.Search) },
            ) + playlistItems + listOf(
                MenuAction(stringResource(R.string.soundalive)) { ui.showDialog(GlobalDialog.SoundAlive) },
                MenuAction(stringResource(R.string.play_speed)) { ui.showDialog(GlobalDialog.PlaySpeed) },
                MenuAction(stringResource(R.string.music_auto_off)) { ui.showDialog(GlobalDialog.SleepTimer) },
                MenuAction(stringResource(R.string.settings)) { ui.navigate(Screen.Settings) },
            ),
            tint = Color(0xFFD0D0D0),
        )
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        TwTabBar(tabs, tab) { tabKey = it.key }
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = S4Colors.Accent, trackColor = Color.Black)
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                LibraryTabId.SONGS -> SongList(library.songs, currentId, indexKeyOf = settings.songSort.indexKey(),
                    actions = actions, emptyText = if (loading) "" else stringResource(R.string.no_songs))
                LibraryTabId.PLAYLISTS -> PlaylistsTab(playlists, library, actions)
                LibraryTabId.ALBUMS -> AlbumsTab(library, actions)
                LibraryTabId.ARTISTS -> ArtistsTab(library, actions)
                LibraryTabId.GENRES -> GroupsTab(library.genres, Icons.Default.Category, stringResource(R.string.no_genres),
                    R.plurals.genres_count, actions) {
                    Screen.Songs(it.name, SongSource.Genre(it.key))
                }
                LibraryTabId.FOLDERS -> GroupsTab(library.folders, Icons.Default.Folder, stringResource(R.string.no_folders),
                    R.plurals.folders_count, actions) {
                    Screen.Songs(it.name, SongSource.Folder(it.key))
                }
                LibraryTabId.COMPOSERS -> GroupsTab(library.composers, Icons.Default.Piano,
                    stringResource(R.string.no_composers), R.plurals.composers_count, actions) {
                    Screen.Songs(it.name, SongSource.Composer(it.key))
                }
                LibraryTabId.MUSIC_SQUARE -> MusicSquareTab(library, currentId, actions = actions)
            }
        }
    }
}

/**
 * TouchWiz icon tabs: four visible at a time, scroll sideways for the rest. Each tab is
 * framed by near-black separators and a black line underneath; the selected tab's blue
 * stays inside its own borders.
 */
@Composable
private fun TwTabBar(tabs: List<LibraryTabId>, selectedTab: LibraryTabId, onSelect: (LibraryTabId) -> Unit) {
    val selected = tabs.indexOf(selectedTab)
    val perScreen = tabs.size.coerceAtMost(4)
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxWidth().background(TabBorder)) {
        // 4 tabs + 3 separators fill the screen exactly, so the scroll stops on tab edges.
        val tabWidth = (maxWidth - TabBorderWidth * (perScreen - 1)) / perScreen
        val stepPx = with(LocalDensity.current) { (tabWidth + TabBorderWidth).roundToPx() }
        LaunchedEffect(selected) {
            scroll.animateScrollTo((selected - 1).coerceIn(0, tabs.size - perScreen) * stepPx)
        }
        Column {
            Row(Modifier.horizontalScroll(scroll).height(76.dp)) {
                tabs.forEachIndexed { i, t ->
                    val isSel = i == selected
                    Column(
                        Modifier.width(tabWidth).fillMaxHeight()
                            .background(if (isSel) TabSelected else TabNormal)
                            .clickable { onSelect(t) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val bitmap = t.icon()
                        if (bitmap != null) {
                            Image(painterResource(bitmap), null, Modifier.size(36.dp), alpha = if (isSel) 1f else 0.85f)
                        } else {
                            Icon(t.vectorIcon(), null, Modifier.size(34.dp),
                                tint = Color(0xFFE8EEF3).copy(alpha = if (isSel) 1f else 0.85f))
                        }
                        Text(stringResource(t.labelRes()), color = Color.White, fontSize = 15.sp, maxLines = 1)
                    }
                    if (i < tabs.lastIndex) Spacer(Modifier.width(TabBorderWidth).fillMaxHeight())
                }
            }
            Spacer(Modifier.fillMaxWidth().height(TabBorderWidth).background(Color.Black))
        }
    }
}

/** Song list with "Shuffle" row, A–Z index strip and a song count footer. */
@Composable
fun SongList(
    songs: List<Song>,
    currentId: Long?,
    /** Text whose first letter drives the A-Z index; must match the list's order. Null = no index. */
    indexKeyOf: ((Song) -> String)?,
    extraMenu: @Composable (Int) -> List<MenuAction> = { emptyList() },
    header: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    emptyText: String = stringResource(R.string.no_songs),
) {
    val ui = LocalUi.current
    val favourites by Favourites.ids.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        ShuffleHeader(if (songs.isEmpty()) null else ({ ui.player.playSongs(songs, shuffle = true) }), actions)
        if (songs.isEmpty() && header == null) {
            EmptyMessage(emptyText)
            return@Column
        }
        val offset = if (header != null) 1 else 0
        val list: @Composable () -> Unit = {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                header?.let { item { it() } }
                itemsIndexed(songs, key = { i, s -> "${s.id}#$i" }) { i, song ->
                    SongRow(song, song.id == currentId, ui.songMenu(song, song.id in favourites, extraMenu(i))) {
                        ui.player.playSongs(songs, i)
                    }
                }
                if (songs.isNotEmpty()) item { CountFooter(songsLabel(songs.size)) }
            }
        }
        if (indexKeyOf != null && songs.isNotEmpty()) {
            val keys = remember(songs, indexKeyOf) { songs.map { indexKey(indexKeyOf(it)) } }
            AlphabetIndexed(keys, scrollTo = { listState.scrollToItem(it + offset) },
                firstVisible = { listState.firstVisibleItemIndex - offset }, content = list)
        } else list()
    }
}

@Composable
private fun PlaylistsTab(playlists: List<Playlist>, library: Library, actions: @Composable RowScope.() -> Unit) {
    val ui = LocalUi.current
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Playlist?>(null) }
    var deleting by remember { mutableStateOf<Playlist?>(null) }
    val favouriteIds by Favourites.ids.collectAsStateWithLifecycle()
    val favouriteCount = favouriteIds.count { it in library.byId }

    Column(Modifier.fillMaxSize()) {
        ShuffleHeader(null, actions)
        LazyColumn(Modifier.fillMaxSize()) {
            item { GroupRow(stringResource(R.string.create_playlist), "", onClick = { creating = true }, icon = Icons.Default.Add) }
            item {
                val title = stringResource(R.string.favourites)
                GroupRow(title, songsLabel(favouriteCount), icon = Icons.Default.Favorite,
                    onClick = { ui.navigate(Screen.Songs(title, SongSource.Favourites)) })
            }
            item {
                val title = stringResource(R.string.most_played)
                GroupRow(title, "", icon = Icons.Default.Star,
                    onClick = { ui.navigate(Screen.Songs(title, SongSource.MostPlayed)) })
            }
            item {
                val title = stringResource(R.string.recently_played)
                GroupRow(title, "", icon = Icons.Default.History,
                    onClick = { ui.navigate(Screen.Songs(title, SongSource.RecentlyPlayed)) })
            }
            item {
                val title = stringResource(R.string.recently_added)
                GroupRow(title, "", icon = Icons.Default.NewReleases,
                    onClick = { ui.navigate(Screen.Songs(title, SongSource.RecentlyAdded)) })
            }
            items(playlists, key = { it.id }) { p ->
                val songs = { p.songIds.mapNotNull { library.byId[it] } }
                GroupRow(
                    title = p.name,
                    subtitle = songsLabel(p.songIds.size),
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    onClick = { ui.navigate(Screen.Songs(p.name, SongSource.UserPlaylist(p.id))) },
                    menu = ui.groupMenu(songs, listOf(
                        MenuAction(stringResource(R.string.edit_title)) { renaming = p },
                        MenuAction(stringResource(R.string.export_playlist)) { ui.exportPlaylist(p.name, songs()) },
                        MenuAction(stringResource(R.string.delete_playlist)) { deleting = p },
                    ), allowDelete = false, allowAddToPlaylist = false),
                )
            }
            item { CountFooter(pluralStringResource(R.plurals.playlists_count, playlists.size, playlists.size)) }
        }
    }

    if (creating) TextInputDialog(stringResource(R.string.create_playlist), stringResource(R.string.default_playlist_name),
        stringResource(R.string.create), onConfirm = { PlaylistStore.create(it); creating = false }, onDismiss = { creating = false })
    renaming?.let { p ->
        TextInputDialog(stringResource(R.string.edit_title), p.name, stringResource(R.string.save),
            onConfirm = { PlaylistStore.rename(p.id, it); renaming = null }, onDismiss = { renaming = null })
    }
    deleting?.let { p ->
        ConfirmDialog(stringResource(R.string.delete_playlist), stringResource(R.string.delete_playlist_message, p.name),
            stringResource(R.string.delete),
            onConfirm = { PlaylistStore.delete(p.id); deleting = null }, onDismiss = { deleting = null })
    }
}

@Composable
private fun AlbumsTab(library: Library, actions: @Composable RowScope.() -> Unit) {
    val ui = LocalUi.current
    val gridState = rememberLazyGridState()
    val haptics = rememberHaptics()
    val keys = remember(library) { library.albums.map { indexKey(it.title) } }
    Column(Modifier.fillMaxSize()) {
        ShuffleHeader(if (library.songs.isEmpty()) null else ({ ui.player.playSongs(library.songs, shuffle = true) }), actions)
        if (library.albums.isEmpty()) {
            EmptyMessage(stringResource(R.string.no_albums))
            return@Column
        }
        AlphabetIndexed(keys, scrollTo = { gridState.scrollToItem(it) }, firstVisible = { gridState.firstVisibleItemIndex }) {
            LazyVerticalGrid(GridCells.Adaptive(108.dp), Modifier.fillMaxSize(), state = gridState,
                contentPadding = PaddingValues(4.dp)) {
                items(library.albums, key = { it.id }) { album ->
                    var menuOpen by remember { mutableStateOf(false) }
                    Column(
                        Modifier.padding(3.dp).combinedClickable(
                            onClick = { ui.navigate(Screen.Songs(album.title, SongSource.Album(album.id))) },
                            onLongClick = { haptics.longPress(); menuOpen = true },
                        )
                    ) {
                        AlbumArt(album.id, Modifier.fillMaxWidth().aspectRatio(1f), sizePx = 320, shape = RectangleShape)
                        Text(album.title, color = Color(0xFFF0F0F0), fontSize = 14.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                        Text(album.artist, color = Color(0xFFA3B0BB), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (menuOpen) ContextMenuDialog(album.title, ui.groupMenu({ album.songs },
                        listOf(MenuAction(stringResource(R.string.change_album_cover)) { ui.changeAlbumCover(album.songs) }))) {
                        menuOpen = false
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    CountFooter(pluralStringResource(R.plurals.albums_count, library.albums.size, library.albums.size))
                }
            }
        }
    }
}

@Composable
private fun ArtistsTab(library: Library, actions: @Composable RowScope.() -> Unit) {
    val ui = LocalUi.current
    val listState = rememberLazyListState()
    val keys = remember(library) { library.artists.map { indexKey(it.name) } }
    Column(Modifier.fillMaxSize()) {
        ShuffleHeader(null, actions)
        if (library.artists.isEmpty()) {
            EmptyMessage(stringResource(R.string.no_artists))
            return@Column
        }
        AlphabetIndexed(keys, scrollTo = { listState.scrollToItem(it) }, firstVisible = { listState.firstVisibleItemIndex }) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(library.artists, key = { it.id }) { artist ->
                    GroupRow(
                        title = artist.name,
                        subtitle = stringResource(R.string.artist_subtitle,
                            pluralStringResource(R.plurals.albums_count, artist.albumCount, artist.albumCount),
                            songsLabel(artist.songs.size)),
                        artAlbumId = artist.songs.first().albumId,
                        onClick = { ui.navigate(Screen.Songs(artist.name, SongSource.Artist(artist.id))) },
                        menu = ui.groupMenu({ artist.songs }),
                    )
                }
                item { CountFooter(pluralStringResource(R.plurals.artists_count, library.artists.size, library.artists.size)) }
            }
        }
    }
}

@Composable
private fun GroupsTab(
    groups: List<SongGroup>,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    emptyText: String,
    @PluralsRes countPlural: Int,
    actions: @Composable RowScope.() -> Unit,
    screenFor: (SongGroup) -> Screen,
) {
    val ui = LocalUi.current
    val listState = rememberLazyListState()
    val keys = remember(groups) { groups.map { indexKey(it.name) } }
    Column(Modifier.fillMaxSize()) {
        ShuffleHeader(null, actions)
        if (groups.isEmpty()) {
            EmptyMessage(emptyText)
            return@Column
        }
        val content: @Composable () -> Unit = {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(groups, key = { it.key }) { g ->
                    GroupRow(
                        title = g.name,
                        subtitle = if (icon == Icons.Default.Folder) g.key else songsLabel(g.songs.size),
                        icon = icon,
                        onClick = { ui.navigate(screenFor(g)) },
                        menu = ui.groupMenu({ g.songs }),
                    )
                }
                item { CountFooter(pluralStringResource(countPlural, groups.size, groups.size)) }
            }
        }
        AlphabetIndexed(keys, scrollTo = { listState.scrollToItem(it) },
            firstVisible = { listState.firstVisibleItemIndex }, content = content)
    }
}

// ---------- Detail list (album / artist / playlist / ...) ----------

@Composable
fun SongsScreen(
    screen: Screen.Songs,
    songs: List<Song>,
    library: Library,
    playlists: List<Playlist>,
    currentId: Long?,
    onBack: () -> Unit,
) {
    val ui = LocalUi.current
    val source = screen.source
    val playlist = (source as? SongSource.UserPlaylist)?.let { s -> playlists.firstOrNull { it.id == s.id } }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        HeaderBar(playlist?.name ?: screen.title, songsLabel(songs.size), onBack = onBack)
        val album = (source as? SongSource.Album)?.let { s -> library.albums.firstOrNull { it.id == s.id } }
        val header: (@Composable () -> Unit)? = album?.let { a ->
            {
                Row(Modifier.fillMaxWidth().background(S4Colors.Surface).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    AlbumArt(a.id, Modifier.size(120.dp), sizePx = 400, shape = RectangleShape)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(a.title, color = S4Colors.PlayerTitle, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(a.artist, color = S4Colors.AlbumText, fontSize = 14.sp, maxLines = 1)
                        if (a.year > 0) Text(a.year.toString(), color = S4Colors.Text2, fontSize = 13.sp)
                    }
                }
            }
        }
        val sort = AppSettings.settings.collectAsStateWithLifecycle().value.songSort
        SongList(
            songs, currentId,
            indexKeyOf = when (source) {
                is SongSource.Artist -> { s -> s.album }   // artist pages are ordered by album
                is SongSource.Genre, is SongSource.Folder, is SongSource.Composer -> sort.indexKey()
                else -> null
            },
            header = header,
            extraMenu = { index ->
                if (playlist != null) {
                    listOf(MenuAction(stringResource(R.string.remove_from_playlist)) { PlaylistStore.removeAt(playlist.id, index) })
                } else emptyList()
            },
            actions = {
                if (songs.isNotEmpty() || playlist != null) {
                    OverflowMenu(
                        ui.groupMenu(
                            { songs },
                            when {
                                playlist != null -> listOf(
                                    MenuAction(stringResource(R.string.edit_title)) { renaming = true },
                                    MenuAction(stringResource(R.string.export_playlist)) { ui.exportPlaylist(playlist.name, songs) },
                                    MenuAction(stringResource(R.string.delete_playlist)) { deleting = true },
                                )
                                source is SongSource.Favourites -> listOf(
                                    MenuAction(stringResource(R.string.export_playlist)) { ui.exportPlaylist(screen.title, songs) },
                                )
                                source is SongSource.Album -> listOf(
                                    MenuAction(stringResource(R.string.change_album_cover)) { ui.changeAlbumCover(songs) },
                                )
                                else -> emptyList()
                            },
                            allowDelete = source is SongSource.Album || source is SongSource.Artist ||
                                source is SongSource.Genre || source is SongSource.Folder || source is SongSource.Composer,
                        ),
                        tint = Color(0xFFD0D0D0),
                    )
                }
            },
        )
    }

    if (renaming && playlist != null) TextInputDialog(stringResource(R.string.edit_title), playlist.name, stringResource(R.string.save),
        onConfirm = { PlaylistStore.rename(playlist.id, it); renaming = false }, onDismiss = { renaming = false })
    if (deleting && playlist != null) ConfirmDialog(stringResource(R.string.delete_playlist),
        stringResource(R.string.delete_playlist_message, playlist.name), stringResource(R.string.delete),
        onConfirm = { PlaylistStore.delete(playlist.id); deleting = false; onBack() }, onDismiss = { deleting = false })
}

// ---------- Search ----------

@Composable
fun SearchScreen(library: Library, currentId: Long?, onBack: () -> Unit) {
    val ui = LocalUi.current
    var query by rememberSaveable { mutableStateOf("") }
    val favourites by Favourites.ids.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val q = query.trim()
    val songs = remember(q, library) {
        if (q.isEmpty()) emptyList() else library.songs.filter {
            it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true)
        }
    }
    val albums = remember(q, library) { if (q.isEmpty()) emptyList() else library.albums.filter { it.title.contains(q, true) } }
    val artists = remember(q, library) { if (q.isEmpty()) emptyList() else library.artists.filter { it.name.contains(q, true) } }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().background(S4Colors.HeaderGradient).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = S4Colors.Text1)
            }
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f).focusRequester(focus),
                placeholder = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, stringResource(R.string.clear), tint = S4Colors.Text2)
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = S4Colors.Accent, cursorColor = S4Colors.Accent,
                    focusedTextColor = S4Colors.PlayerTitle, unfocusedTextColor = S4Colors.PlayerTitle,
                ),
            )
        }
        if (q.isNotEmpty() && songs.isEmpty() && albums.isEmpty() && artists.isEmpty()) {
            EmptyMessage(stringResource(R.string.no_results))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (artists.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.section_artists, artists.size)) }
                items(artists, key = { "ar${it.id}" }) { a ->
                    GroupRow(a.name, songsLabel(a.songs.size), artAlbumId = a.songs.first().albumId,
                        onClick = { ui.navigate(Screen.Songs(a.name, SongSource.Artist(a.id))) })
                }
            }
            if (albums.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.section_albums, albums.size)) }
                items(albums, key = { "al${it.id}" }) { a ->
                    GroupRow(a.title, a.artist, artAlbumId = a.id,
                        onClick = { ui.navigate(Screen.Songs(a.title, SongSource.Album(a.id))) })
                }
            }
            if (songs.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.section_songs, songs.size)) }
                itemsIndexed(songs, key = { _, s -> "s${s.id}" }) { i, s ->
                    SongRow(s, s.id == currentId, ui.songMenu(s, s.id in favourites)) { ui.player.playSongs(songs, i) }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, color = NowPlayingBlue, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.fillMaxWidth().background(Color(0xFF1C1E21)).padding(horizontal = 16.dp, vertical = 6.dp))
}
