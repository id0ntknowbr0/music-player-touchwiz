package dev.s4music.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.s4music.player.data.Playlist
import dev.s4music.player.data.PlaylistStore
import dev.s4music.player.data.Song
import dev.s4music.player.playback.PlaybackBridge
import dev.s4music.player.playback.S4_BANDS_HZ
import dev.s4music.player.playback.SoundPreset
import dev.s4music.player.ui.theme.S4Colors
import kotlin.math.roundToInt

private val sliderColors @Composable get() = SliderDefaults.colors(
    thumbColor = S4Colors.AccentBright,
    activeTrackColor = S4Colors.AccentBright,
    inactiveTrackColor = Color(0xFF3A3F44),
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent,
)

// ---------- SoundAlive ----------

@Composable
fun SoundAliveDialog(onDismiss: () -> Unit) {
    val s by PlaybackBridge.soundAlive.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("SoundAlive") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SoundPreset.entries.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { p ->
                            PresetChip(p.label, s.preset == p, Modifier.weight(1f)) {
                                PlaybackBridge.setSoundAlive(PlaybackBridge.soundAlive.value.copy(preset = p))
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                if (s.preset == SoundPreset.CUSTOM) {
                    Spacer(Modifier.height(8.dp))
                    S4_BANDS_HZ.forEachIndexed { i, hz ->
                        LabeledSlider(
                            label = if (hz >= 1000) "${hz / 1000f}k".replace(".0k", "k") else "$hz",
                            value = s.customGains[i],
                            range = -10f..10f,
                            valueText = "%+d dB".format(s.customGains[i].roundToInt()),
                        ) { v ->
                            val cur = PlaybackBridge.soundAlive.value
                            PlaybackBridge.setSoundAlive(cur.copy(
                                customGains = cur.customGains.toMutableList().also { it[i] = v.roundToInt().toFloat() }))
                        }
                    }
                    LabeledSlider("Bass", s.customBass.toFloat(), 0f..1000f, "${s.customBass / 10}%") { v ->
                        PlaybackBridge.setSoundAlive(PlaybackBridge.soundAlive.value.copy(customBass = v.toInt()))
                    }
                    LabeledSlider("3D", s.customVirtualizer.toFloat(), 0f..1000f, "${s.customVirtualizer / 10}%") { v ->
                        PlaybackBridge.setSoundAlive(PlaybackBridge.soundAlive.value.copy(customVirtualizer = v.toInt()))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Effects use Android's built-in audio effects, so results vary between devices and outputs.",
                    color = S4Colors.Text2, fontSize = 12.sp,
                )
            }
        },
    )
}

@Composable
private fun PresetChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier.clip(shape)
            .background(if (selected) S4Colors.Accent else S4Colors.CountInfoBg)
            .border(1.dp, if (selected) S4Colors.AccentBright else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Color.White else S4Colors.Text1, fontSize = 12.sp,
            textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = S4Colors.Text1, fontSize = 13.sp, modifier = Modifier.width(44.dp))
        Slider(value, onChange, Modifier.weight(1f), valueRange = range, colors = sliderColors)
        Text(valueText, color = S4Colors.Text2, fontSize = 12.sp, modifier = Modifier.width(52.dp), textAlign = TextAlign.End)
    }
}

// ---------- Play speed (0.5x – 2.0x in 0.1 steps, as on the S4) ----------

@Composable
fun PlaySpeedDialog(current: Float, onSet: (Float) -> Unit, onDismiss: () -> Unit) {
    var speed by remember { mutableFloatStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Play speed") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("%.1fx".format(speed), color = S4Colors.AccentBright, fontSize = 28.sp)
                Slider(
                    value = speed,
                    onValueChange = { speed = (it * 10).roundToInt() / 10f; onSet(speed) },
                    valueRange = 0.5f..2.0f,
                    steps = 14,
                    colors = sliderColors,
                )
                Row(Modifier.fillMaxWidth()) {
                    Text("0.5x", color = S4Colors.Text2, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Text("2.0x", color = S4Colors.Text2, fontSize = 12.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = { speed = 1f; onSet(1f) }) { Text("Reset") } },
    )
}

// ---------- Music auto off ----------

@Composable
fun SleepTimerDialog(onSet: (Int?) -> Unit, onDismiss: () -> Unit) {
    val remaining = rememberSleepRemaining()
    val options = listOf(
        null to "Off", 15 to "After 15 mins", 30 to "After 30 mins",
        60 to "After 1 hr", 90 to "After 1 hr 30 mins", 120 to "After 2 hrs",
    )
    var custom by remember { mutableFloatStateOf(45f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Music auto off") },
        text = {
            Column {
                if (remaining != null) {
                    Text("Music will stop in ${formatTime(remaining)}", color = S4Colors.AccentBright, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                }
                options.forEach { (minutes, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSet(minutes); onDismiss() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = minutes == null && remaining == null,
                            onClick = { onSet(minutes); onDismiss() },
                            colors = RadioButtonDefaults.colors(selectedColor = S4Colors.Accent),
                        )
                        Text(label, color = S4Colors.Text1)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = S4Colors.Divider)
                Text("Custom: ${custom.roundToInt()} mins", color = S4Colors.Text1, fontSize = 14.sp)
                Slider(custom, { custom = it }, valueRange = 1f..240f, colors = sliderColors)
            }
        },
        confirmButton = { TextButton(onClick = { onSet(custom.roundToInt()); onDismiss() }) { Text("Set custom") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------- Playlists ----------

@Composable
fun AddToPlaylistDialog(playlists: List<Playlist>, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        TextInputDialog("Create playlist", "My playlist", "Create",
            onConfirm = { onPick(PlaylistStore.create(it).id) }, onDismiss = onDismiss)
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item {
                    Text("+  Create playlist", color = S4Colors.Accent, fontSize = 16.sp,
                        modifier = Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 12.dp))
                }
                items(playlists, key = { it.id }) { p ->
                    Text(p.name, color = S4Colors.Text1, fontSize = 16.sp,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(p.id) }.padding(vertical = 12.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------- Details (the S4's MediaInfoActivity) ----------

@Composable
fun DetailsDialog(song: Song, onDismiss: () -> Unit) {
    val rows = listOf(
        "Title" to song.title,
        "Artist" to song.artist,
        "Album" to song.album,
        "Genre" to song.genre,
        "Year" to (song.year.takeIf { it > 0 }?.toString() ?: "-"),
        "Track" to (song.track.takeIf { it > 0 }?.let { (it % 1000).toString() } ?: "-"),
        "Length" to formatTime(song.durationMs),
        "Location" to song.folder.ifEmpty { "/" },
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Details") },
        text = {
            Column {
                rows.forEach { (k, v) ->
                    Text(k, color = S4Colors.Text2, fontSize = 12.sp)
                    Text(v, color = S4Colors.Text1, fontSize = 15.sp, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
