package dev.s4music.player.ui

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.s4music.player.R
import dev.s4music.player.data.RingtoneMaker
import dev.s4music.player.data.Song
import dev.s4music.player.ui.theme.S4Colors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val KINDS = listOf(
    RingtoneMaker.Kind.RINGTONE to R.string.set_as_ringtone,
    RingtoneMaker.Kind.NOTIFICATION to R.string.set_as_notification,
    RingtoneMaker.Kind.ALARM to R.string.set_as_alarm,
)

/** Clip lengths in seconds; null = to the end of the song. */
private val LENGTHS = listOf<Int?>(null, 30, 15, 5)

/** "Set as": phone ringtone, notification or alarm sound, with a start point and length. */
@Composable
fun SetAsDialog(song: Song, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val ui = LocalUi.current
    val scope = rememberCoroutineScope()
    val trimmable = remember(song) { RingtoneMaker.canTrim(song) }
    var kind by remember { mutableStateOf(RingtoneMaker.Kind.RINGTONE) }
    var startMs by remember { mutableFloatStateOf(0f) }
    var lengthSec by remember { mutableStateOf<Int?>(null) }
    var askPermission by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<MediaPlayer?>(null) }

    fun stopPreview() {
        preview?.let { runCatching { it.stop() }; it.release() }
        preview = null
    }
    DisposableEffect(Unit) { onDispose { stopPreview() } }

    val endMs: Long? = lengthSec?.let { (startMs.toLong() + it * 1000L).coerceAtMost(song.durationMs) }

    fun apply() {
        busy = true
        stopPreview()
        scope.launch {
            val ok = runCatching { RingtoneMaker.apply(context, song, kind, startMs.toLong(), endMs) }.getOrDefault(false)
            busy = false
            Toast.makeText(context,
                if (ok) context.getString(R.string.set_as_done, context.getString(KINDS.first { it.first == kind }.second))
                else context.getString(R.string.set_as_failed),
                Toast.LENGTH_SHORT).show()
            if (ok) onDismiss()
        }
    }

    // Android only lets an app change the default sounds after the user allows "Modify system settings".
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (RingtoneMaker.canWriteSettings(context)) apply()
        else Toast.makeText(context, R.string.set_as_permission_denied, Toast.LENGTH_SHORT).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_as)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                KINDS.forEach { (k, label) ->
                    Row(Modifier.fillMaxWidth().clickable { kind = k }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(kind == k, { kind = k }, colors = RadioButtonDefaults.colors(selectedColor = S4Colors.Accent))
                        Text(stringResource(label), color = S4Colors.Text1)
                    }
                }
                if (trimmable && song.durationMs > 2_000) {
                    Text(stringResource(R.string.set_as_start, formatTime(startMs.toLong())), color = S4Colors.Text1,
                        fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
                    Slider(
                        value = startMs,
                        onValueChange = { startMs = (it / 500).toInt() * 500f; stopPreview() },
                        valueRange = 0f..(song.durationMs - 1_000).toFloat(),
                        colors = SliderDefaults.colors(thumbColor = S4Colors.AccentBright, activeTrackColor = S4Colors.AccentBright,
                            inactiveTrackColor = Color(0xFF3A3F44)),
                    )
                    Text(stringResource(R.string.set_as_length), color = S4Colors.Text1, fontSize = 14.sp)
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LENGTHS.forEach { len ->
                            PresetChip(
                                if (len == null) stringResource(R.string.set_as_to_end)
                                else pluralStringResource(R.plurals.seconds_short, len, len),
                                lengthSec == len, Modifier.weight(1f),
                            ) { lengthSec = len }
                        }
                    }
                    TextButton(onClick = {
                        if (preview != null) stopPreview() else {
                            ui.player.pause()
                            val mp = runCatching {
                                MediaPlayer.create(context, song.uri)?.apply { seekTo(startMs.toInt()); start() }
                            }.getOrNull()
                            preview = mp
                            if (mp != null) scope.launch {
                                // Preview at most 10 s (or the clip length).
                                delay(minOf(10_000L, (endMs ?: song.durationMs) - startMs.toLong()).coerceAtLeast(500))
                                if (preview === mp) stopPreview()
                            }
                        }
                    }) {
                        Text(stringResource(if (preview != null) R.string.set_as_stop_preview else R.string.set_as_preview),
                            color = S4Colors.Accent)
                    }
                } else {
                    Text(stringResource(R.string.set_as_whole_song), color = S4Colors.Text2, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (RingtoneMaker.canWriteSettings(context)) apply() else askPermission = true
            }) { Text(stringResource(R.string.set_as_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )

    if (askPermission) {
        AlertDialog(
            onDismissRequest = { askPermission = false },
            title = { Text(stringResource(R.string.set_as_permission_title)) },
            text = { Text(stringResource(R.string.set_as_permission_message)) },
            confirmButton = {
                TextButton(onClick = {
                    askPermission = false
                    permissionLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
                }) { Text(stringResource(R.string.open_settings)) }
            },
            dismissButton = { TextButton(onClick = { askPermission = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
