package dev.s4music.player.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.s4music.player.R
import dev.s4music.player.data.AppSettings
import dev.s4music.player.data.LibraryTabId
import dev.s4music.player.data.SongSort
import dev.s4music.player.ui.theme.S4Colors
import kotlin.math.roundToInt

@StringRes
fun LibraryTabId.labelRes(): Int = when (this) {
    LibraryTabId.SONGS -> R.string.tab_songs
    LibraryTabId.PLAYLISTS -> R.string.tab_playlists
    LibraryTabId.ALBUMS -> R.string.tab_albums
    LibraryTabId.ARTISTS -> R.string.tab_artists
    LibraryTabId.GENRES -> R.string.tab_genres
    LibraryTabId.FOLDERS -> R.string.tab_folders
}

@StringRes
private fun SongSort.labelRes(): Int = when (this) {
    SongSort.TITLE -> R.string.sort_title
    SongSort.ARTIST -> R.string.sort_artist
    SongSort.ALBUM -> R.string.sort_album
    SongSort.DATE_ADDED -> R.string.sort_date_added
}

private val MIN_DURATION_CHOICES = listOf(0, 10, 30, 45, 60, 90, 120)

private enum class SettingsDialog { Tabs, Folders, MinDuration, Sort, Crossfade }

/** TouchWiz-style settings list: blue section headers, two-line rows, checkboxes on the right. */
@Composable
fun SettingsScreen(libraryVm: LibraryViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val s by AppSettings.settings.collectAsStateWithLifecycle()
    val folders by libraryVm.allFolders.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        HeaderBar(stringResource(R.string.settings), onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsSection(R.string.settings_section_library)
            SettingsRow(
                stringResource(R.string.settings_tabs),
                s.visibleTabs.joinToString(", ") { context.getString(it.labelRes()) },
            ) { dialog = SettingsDialog.Tabs }
            val hidden = folders.count { s.isExcluded(it.path) }
            SettingsRow(
                stringResource(R.string.settings_scan_folders),
                if (hidden == 0) stringResource(R.string.settings_scan_folders_all)
                else pluralStringResource(R.plurals.settings_scan_folders_hidden, hidden, hidden),
            ) { dialog = SettingsDialog.Folders }
            SettingsRow(stringResource(R.string.settings_min_duration), minDurationLabel(s.minDurationSec)) {
                dialog = SettingsDialog.MinDuration
            }
            SettingsRow(stringResource(R.string.settings_sort), stringResource(s.songSort.labelRes())) {
                dialog = SettingsDialog.Sort
            }

            SettingsSection(R.string.settings_section_playback)
            SettingsRow(stringResource(R.string.settings_crossfade), crossfadeLabel(s.crossfadeSec)) {
                dialog = SettingsDialog.Crossfade
            }
            SettingsRow(
                stringResource(R.string.settings_skip_silence),
                stringResource(R.string.settings_skip_silence_summary),
                checked = s.skipSilence,
            ) { AppSettings.update { it.copy(skipSilence = !it.skipSilence) } }

            SettingsSection(R.string.settings_section_lyrics)
            SettingsRow(
                stringResource(R.string.settings_lyrics_online),
                stringResource(R.string.settings_lyrics_online_summary),
                checked = s.lyricsOnline,
            ) { AppSettings.update { it.copy(lyricsOnline = !it.lyricsOnline) } }

            SettingsSection(R.string.settings_section_about)
            SettingsRow(stringResource(R.string.app_name), stringResource(R.string.settings_version, version), onClick = null)
        }
    }

    when (dialog) {
        SettingsDialog.Tabs -> CheckListDialog(
            title = stringResource(R.string.settings_tabs),
            hint = stringResource(R.string.settings_tabs_hint),
            items = LibraryTabId.entries,
            label = { stringResource(it.labelRes()) },
            isChecked = { it !in s.hiddenTabs },
            onToggle = { tab ->
                AppSettings.update { cur ->
                    val hiddenNow = if (tab in cur.hiddenTabs) cur.hiddenTabs - tab else cur.hiddenTabs + tab
                    if (hiddenNow.size == LibraryTabId.entries.size) cur else cur.copy(hiddenTabs = hiddenNow)
                }
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.Folders -> CheckListDialog(
            title = stringResource(R.string.settings_scan_folders),
            hint = stringResource(
                if (folders.isEmpty()) R.string.settings_scan_folders_empty else R.string.settings_scan_folders_hint),
            items = folders,
            label = { it.path.ifEmpty { stringResource(R.string.internal_storage) } },
            sublabel = { pluralStringResource(R.plurals.songs_count, it.songCount, it.songCount) },
            isChecked = { !s.isExcluded(it.path) },
            // An excluded parent folder hides its subfolders too, so they can't be re-ticked on their own.
            isEnabled = { f -> s.excludedFolders.none { ex -> f.path.startsWith("$ex/") } },
            onToggle = { f ->
                AppSettings.update { cur ->
                    cur.copy(excludedFolders =
                        if (f.path in cur.excludedFolders) cur.excludedFolders - f.path else cur.excludedFolders + f.path)
                }
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.MinDuration -> RadioListDialog(
            title = stringResource(R.string.settings_min_duration),
            hint = stringResource(R.string.settings_min_duration_hint),
            items = MIN_DURATION_CHOICES,
            selected = s.minDurationSec,
            label = { minDurationLabel(it) },
            onPick = { v -> AppSettings.update { it.copy(minDurationSec = v) }; dialog = null },
            onDismiss = { dialog = null },
        )
        SettingsDialog.Sort -> RadioListDialog(
            title = stringResource(R.string.settings_sort),
            hint = null,
            items = SongSort.entries,
            selected = s.songSort,
            label = { stringResource(it.labelRes()) },
            onPick = { v -> AppSettings.update { it.copy(songSort = v) }; dialog = null },
            onDismiss = { dialog = null },
        )
        SettingsDialog.Crossfade -> CrossfadeDialog(s.crossfadeSec, onDismiss = { dialog = null })
        null -> Unit
    }
}

@Composable
private fun crossfadeLabel(sec: Int) =
    if (sec == 0) stringResource(R.string.settings_crossfade_off)
    else pluralStringResource(R.plurals.settings_crossfade_value, sec, sec)

@Composable
private fun CrossfadeDialog(initial: Int, onDismiss: () -> Unit) {
    var sec by remember { mutableFloatStateOf(initial.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_crossfade)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(crossfadeLabel(sec.roundToInt()), color = S4Colors.AccentBright, fontSize = 22.sp)
                Slider(
                    value = sec,
                    onValueChange = { sec = it.roundToInt().toFloat() },
                    valueRange = 0f..12f,
                    steps = 11,
                    colors = SliderDefaults.colors(
                        thumbColor = S4Colors.AccentBright, activeTrackColor = S4Colors.AccentBright,
                        inactiveTrackColor = Color(0xFF3A3F44),
                        activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
                    ),
                )
                Text(stringResource(R.string.settings_crossfade_hint), color = S4Colors.Text2, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                AppSettings.update { it.copy(crossfadeSec = sec.roundToInt()) }
                onDismiss()
            }) { Text(stringResource(R.string.done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun minDurationLabel(sec: Int) =
    if (sec == 0) stringResource(R.string.settings_min_duration_off)
    else pluralStringResource(R.plurals.settings_min_duration_value, sec, sec)

@Composable
private fun SettingsSection(@StringRes title: Int) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text(stringResource(title), color = NowPlayingBlue, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        HorizontalDivider(color = NowPlayingBlue, thickness = 2.dp)
    }
}

@Composable
private fun SettingsRow(title: String, summary: String, checked: Boolean? = null, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color(0xFFF0F0F0), fontSize = 18.sp)
            if (summary.isNotEmpty()) Text(summary, color = Color(0xFFA3B0BB), fontSize = 14.sp)
        }
        if (checked != null) {
            Spacer(Modifier.width(8.dp))
            Checkbox(checked, { onClick?.invoke() }, colors = checkboxColors())
        }
    }
    HorizontalDivider(color = Color(0xFF383838), thickness = 1.dp)
}

@Composable
private fun checkboxColors() = CheckboxDefaults.colors(
    checkedColor = S4Colors.Accent, uncheckedColor = S4Colors.Text2, checkmarkColor = Color.White,
)

@Composable
private fun <T> CheckListDialog(
    title: String,
    hint: String,
    items: List<T>,
    label: @Composable (T) -> String,
    sublabel: (@Composable (T) -> String)? = null,
    isChecked: (T) -> Boolean,
    isEnabled: (T) -> Boolean = { true },
    onToggle: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(hint, color = S4Colors.Text2, fontSize = 13.sp)
                LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 6.dp)) {
                    items(items) { item ->
                        val enabled = isEnabled(item)
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = enabled) { onToggle(item) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(isChecked(item) && enabled, { onToggle(item) }, enabled = enabled, colors = checkboxColors())
                            Column(Modifier.weight(1f)) {
                                Text(label(item), color = if (enabled) S4Colors.Text1 else S4Colors.Disabled, fontSize = 15.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                sublabel?.let { Text(it(item), color = S4Colors.Text2, fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}

@Composable
private fun <T> RadioListDialog(
    title: String,
    hint: String?,
    items: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                hint?.let { Text(it, color = S4Colors.Text2, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp)) }
                items.forEach { item ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(item) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(item == selected, { onPick(item) },
                            colors = RadioButtonDefaults.colors(selectedColor = S4Colors.Accent))
                        Text(label(item), color = S4Colors.Text1)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ---------- Playlist import report ----------

/** One imported file: [counts] is (entries in the file, songs matched), or null if it couldn't be read. */
data class ImportResult(val name: String, val counts: Pair<Int, Int>?, val unmatched: List<String>)

@Composable
fun ImportReportDialog(results: List<ImportResult>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_report_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                results.forEach { r ->
                    item {
                        val line = r.counts?.let { (total, matched) ->
                            pluralStringResource(R.plurals.import_report_line, total, r.name, matched, total)
                        } ?: stringResource(R.string.import_report_unreadable, r.name)
                        Text(line, color = S4Colors.Text1, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                        if (r.unmatched.isNotEmpty()) {
                            Text(stringResource(R.string.import_report_unmatched), color = S4Colors.Text2, fontSize = 13.sp,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    items(r.unmatched) { path ->
                        Text(path, color = S4Colors.Text2, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}
