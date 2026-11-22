package dev.s4music.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.s4music.player.R
import dev.s4music.player.data.Library
import dev.s4music.player.data.MoodCell
import dev.s4music.player.data.MoodClassifier
import dev.s4music.player.data.MoodFeatures
import dev.s4music.player.data.MoodStore
import dev.s4music.player.data.MoodWorker
import dev.s4music.player.data.Song
import dev.s4music.player.ui.theme.S4Colors
import kotlin.math.sqrt

// Corner colours of the square: calm/exciting across, passionate (top) / joyful (bottom) down.
private val CalmPassionate = Color(0xFF6A3FA0)
private val ExcitingPassionate = Color(0xFFD8344A)
private val CalmJoyful = Color(0xFF2FA6C9)
private val ExcitingJoyful = Color(0xFFF2A531)
private val EmptyCell = Color(0xFF1E2124)

private fun cellColor(cell: MoodCell): Color {
    val tx = cell.x / (MoodCell.SIZE - 1f)
    val ty = cell.y / (MoodCell.SIZE - 1f)
    return lerp(lerp(CalmPassionate, ExcitingPassionate, tx), lerp(CalmJoyful, ExcitingJoyful, tx), ty)
}

/** Lays text out at its full width, then turns it on its side (for the square's side labels). */
private fun Modifier.vertical(degrees: Float) = rotate(degrees).layout { measurable, _ ->
    val p = measurable.measure(Constraints())
    layout(p.height, p.width) { p.place((p.height - p.width) / 2, (p.width - p.height) / 2) }
}

/** Songs per Music Square cell, plus how many songs couldn't be placed yet. */
data class MoodGrid(val cells: Map<Int, List<Song>>, val unclassified: Int) {
    val max = cells.values.maxOfOrNull { it.size } ?: 0
}

fun buildMoodGrid(songs: List<Song>, features: Map<Long, MoodFeatures>): MoodGrid {
    var unclassified = 0
    val cells = HashMap<Int, MutableList<Song>>()
    songs.forEach { s ->
        val cell = MoodClassifier.cell(s, features[s.id])
        if (cell == null) unclassified++ else cells.getOrPut(cell.index) { ArrayList() } += s
    }
    return MoodGrid(cells, unclassified)
}

/**
 * The S4's Music Square: a 5×5 mood grid. Tap a square, or drag across several, to make a
 * playlist of the songs in them; the songs appear underneath.
 */
@Composable
fun MusicSquareTab(
    library: Library,
    currentId: Long?,
    initialSelection: List<Int> = emptyList(),
    actions: @Composable RowScope.() -> Unit,
) {
    val context = LocalContext.current
    val features by MoodStore.features.collectAsStateWithLifecycle()
    val progress by remember { MoodWorker.analysisProgress(context) }.collectAsStateWithLifecycle(null)
    var selected by rememberSaveable { mutableStateOf(initialSelection) }
    val grid = remember(library.songs, features) { buildMoodGrid(library.songs, features) }
    val songs = remember(grid, selected) {
        selected.sorted().flatMap { grid.cells[it].orEmpty() }.sortedBy { it.title.lowercase() }
    }
    val notAnalysed = remember(library.songs, features) { library.songs.count { features[it.id]?.analysed != true } }

    LaunchedEffect(Unit) { MoodWorker.startQuickPass(context) }

    SongList(
        songs = songs,
        currentId = currentId,
        indexKeyOf = null,
        actions = actions,
        header = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                MoodSquare(grid, selected.toSet(), onSelectionChange = { selected = it.toList() })
                Spacer(Modifier.padding(top = 6.dp))
                Text(
                    when {
                        selected.isEmpty() -> stringResource(R.string.square_hint)
                        else -> pluralStringResource(R.plurals.square_selected, songs.size, songs.size)
                    },
                    color = S4Colors.Text1, fontSize = 14.sp, textAlign = TextAlign.Center,
                )
                if (grid.unclassified > 0) {
                    Text(pluralStringResource(R.plurals.square_unclassified, grid.unclassified, grid.unclassified),
                        color = S4Colors.Text2, fontSize = 12.sp, textAlign = TextAlign.Center)
                }
                AnalysisRow(progress, notAnalysed)
            }
        },
        emptyText = "",
    )
}

@Composable
private fun AnalysisRow(progress: Pair<Int, Int>?, notAnalysed: Int) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            progress != null -> {
                val (done, total) = progress
                Text(
                    if (total > 0) stringResource(R.string.square_analysing, done, total)
                    else stringResource(R.string.square_analysis_waiting),
                    color = S4Colors.AccentBright, fontSize = 13.sp,
                )
                TextButton(onClick = { MoodWorker.cancelAnalysis(context) }) {
                    Text(stringResource(R.string.square_stop), color = S4Colors.Text2)
                }
            }
            notAnalysed > 0 -> TextButton(onClick = { MoodWorker.startAnalysis(context) }) {
                Text(pluralStringResource(R.plurals.square_analyse, notAnalysed, notAnalysed), color = S4Colors.Accent)
            }
        }
    }
}

@Composable
private fun MoodSquare(grid: MoodGrid, selected: Set<Int>, onSelectionChange: (Set<Int>) -> Unit) {
    val currentSelected by rememberUpdatedState(selected)
    val currentOnChange by rememberUpdatedState(onSelectionChange)
    val haptics = rememberHaptics()
    val axis = S4Colors.Text2
    Column(Modifier.widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.square_passionate), color = axis, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(grownBy(22.dp, 24f)), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.square_calm), color = axis, fontSize = 12.sp, maxLines = 1,
                    modifier = Modifier.vertical(-90f))
            }
            val description = stringResource(R.string.square_description)
            Canvas(
                Modifier.weight(1f).aspectRatio(1f)
                    .semantics { contentDescription = description }
                    .pointerInput(Unit) {
                        fun cellAt(o: Offset): Int {
                            val x = (o.x / size.width * MoodCell.SIZE).toInt().coerceIn(0, MoodCell.SIZE - 1)
                            val y = (o.y / size.height * MoodCell.SIZE).toInt().coerceIn(0, MoodCell.SIZE - 1)
                            return MoodCell(x, y).index
                        }
                        // Tap toggles a square; dragging selects (or clears) every square it passes over,
                        // depending on whether the first square was off (or on).
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val first = cellAt(down.position)
                            val adding = first !in currentSelected
                            var sel = currentSelected.toMutableSet().apply { if (adding) add(first) else remove(first) }
                            val touched = mutableSetOf(first)
                            currentOnChange(sel.toSet())
                            haptics.tick()
                            down.consume()
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull() ?: break
                                if (!change.pressed) break
                                val c = cellAt(change.position)
                                if (touched.add(c)) {
                                    sel = sel.apply { if (adding) add(c) else remove(c) }
                                    currentOnChange(sel.toSet())
                                    haptics.tick()
                                }
                                change.consume()
                            }
                        }
                    },
            ) {
                val gap = 3.dp.toPx()
                val cellSize = (size.width - gap * (MoodCell.SIZE - 1)) / MoodCell.SIZE
                val corner = CornerRadius(4.dp.toPx())
                for (i in 0 until MoodCell.SIZE * MoodCell.SIZE) {
                    val cell = MoodCell.fromIndex(i)
                    val count = grid.cells[i]?.size ?: 0
                    val topLeft = Offset(cell.x * (cellSize + gap), cell.y * (cellSize + gap))
                    val fill = if (count == 0) EmptyCell else {
                        // More songs → stronger colour, like the original.
                        val strength = 0.25f + 0.75f * sqrt(count.toFloat() / grid.max.coerceAtLeast(1))
                        lerp(EmptyCell, cellColor(cell), strength)
                    }
                    drawRoundRect(fill, topLeft, Size(cellSize, cellSize), corner)
                    if (i in selected) {
                        drawRoundRect(Color.White, topLeft + Offset(1.5f, 1.5f), Size(cellSize - 3f, cellSize - 3f), corner,
                            style = Stroke(width = 3.dp.toPx()))
                    }
                }
            }
            Box(Modifier.width(grownBy(22.dp, 24f)), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.square_exciting), color = axis, fontSize = 12.sp, maxLines = 1,
                    modifier = Modifier.vertical(90f))
            }
        }
        Text(stringResource(R.string.square_joyful), color = axis, fontSize = 12.sp)
    }
}
