package dev.s4music.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
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

// Colours measured from the Galaxy S4 / Note 3 Music Square.
private val SquareBackground = Color(0xFF060606)
private val AxisLabel = Color(0xFF959DAA)
/** Unlit squares: one gradient across the whole grid, lighter at the top right. */
private val GridLight = Color(0xFF2B3C4C)
private val GridDark = Color(0xFF12202B)
private val GridEdge = Color(0xFF3A4450)
private val SeamDark = Color(0x990A1620)
private val SeamLight = Color(0x40506070)
/** A pressed square glows cyan, brightest in the middle. */
private val GlowCentre = Color(0xFF207A9C)
private val GlowMiddle = Color(0xFF14668A)
private val GlowEdge = Color(0xFF0A4A66)
private val GlowSeam = Color(0xFF00485F)

/**
 * The S4 lays the square out with Exciting at the top, Calm at the bottom, Passionate on the
 * left and Joyful on the right. [MoodCell] stores x = calm..exciting, y = passionate..joyful.
 */
private fun MoodCell.column() = y
private fun MoodCell.row() = MoodCell.SIZE - 1 - x
private fun cellAtScreen(column: Int, row: Int) = MoodCell(MoodCell.SIZE - 1 - row, column)

/** Lays text out at its full width, then turns it on its side (for the square's side labels). */
private fun Modifier.vertical(degrees: Float) = rotate(degrees).layout { measurable, _ ->
    val p = measurable.measure(Constraints())
    layout(p.height, p.width) { p.place((p.height - p.width) / 2, (p.width - p.height) / 2) }
}

/** Songs per Music Square cell, plus how many songs couldn't be placed yet. */
data class MoodGrid(val cells: Map<Int, List<Song>>, val unclassified: Int)

fun buildMoodGrid(songs: List<Song>, features: Map<Long, MoodFeatures>): MoodGrid {
    var unclassified = 0
    val cells = HashMap<Int, MutableList<Song>>()
    songs.forEach { s ->
        val cell = MoodClassifier.cell(s, features[s.id])
        if (cell == null) unclassified++ else cells.getOrPut(cell.index) { ArrayList() } += s
    }
    return MoodGrid(cells, unclassified)
}

/** What the pressed squares play, and which of them have songs ([lit]). */
data class MoodPick(val songs: List<Song>, val lit: Set<Int>)

/**
 * Every song in the pressed squares, shuffled into one queue: songs that share a mood share a
 * square, and they all play. Squares never borrow songs from their neighbours, so an empty
 * square plays nothing and the tab says there are no songs for that mood.
 */
fun moodPick(grid: MoodGrid, selected: Collection<Int>, random: kotlin.random.Random = kotlin.random.Random): MoodPick {
    val lit = selected.filter { !grid.cells[it].isNullOrEmpty() }
    return MoodPick(lit.flatMap { grid.cells[it].orEmpty() }.shuffled(random), lit.toSet())
}

/**
 * The S4's Music Square: a 5×5 mood grid that starts with nothing pressed. Press a square, or drag
 * across several, and they stay pressed; their songs start playing. Pressing an empty square
 * still presses it and says no songs match, leaving the music that's playing alone. Pressing
 * another square releases the old one. Songs are analysed automatically in the background.
 */
@Composable
fun MusicSquareTab(
    library: Library,
    @Suppress("UNUSED_PARAMETER") currentId: Long?,
    initialSelection: List<Int> = emptyList(),
    actions: @Composable RowScope.() -> Unit,
) {
    val context = LocalContext.current
    val ui = LocalUi.current
    val features by MoodStore.features.collectAsStateWithLifecycle()
    val progress by remember { MoodWorker.analysisProgress(context) }.collectAsStateWithLifecycle(null)
    val grid = remember(library.songs, features) { buildMoodGrid(library.songs, features) }
    /** Squares under the finger right now (null when no finger is down). */
    var touching by remember { mutableStateOf<Set<Int>?>(null) }
    /** The squares pressed by the last tap or drag; they stay pressed until the next one. */
    var pressed by rememberSaveable { mutableStateOf(initialSelection) }

    LaunchedEffect(Unit) {
        MoodWorker.startQuickPass(context)
        MoodWorker.startAnalysis(context)
    }

    val shown = touching ?: pressed.toSet()
    val message = if (shown.isNotEmpty() && shown.all { grid.cells[it].isNullOrEmpty() }) {
        stringResource(R.string.square_no_songs)
    } else null

    Box(Modifier.fillMaxSize().background(SquareBackground)) {
        MoodSquare(
            lit = shown,
            message = message,
            onTouch = { touching = it },
            onPick = { cells ->
                touching = null
                pressed = cells.toList()
                val pick = moodPick(grid, cells)
                // An empty square is pressed but plays nothing; whatever was playing carries on.
                if (pick.songs.isNotEmpty()) ui.player.playSongs(pick.songs)
            },
            modifier = Modifier.align(Alignment.Center),
        )
        Row(Modifier.align(Alignment.TopEnd), content = actions)
        progress?.let { (done, total) ->
            Text(
                if (total > 0) stringResource(R.string.square_analysing, done, total)
                else stringResource(R.string.square_analysis_waiting),
                color = AxisLabel, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun MoodSquare(
    lit: Set<Int>,
    /** Shown under the square, e.g. when the touched square has no songs. */
    message: String?,
    /** The squares touched so far in this gesture. */
    onTouch: (Set<Int>) -> Unit,
    /** Called when the finger lifts, with every square touched: start playing them. */
    onPick: (Set<Int>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnTouch by rememberUpdatedState(onTouch)
    val currentOnPick by rememberUpdatedState(onPick)
    val haptics = rememberHaptics()
    val label: @Composable (Int, Modifier) -> Unit = { id, m ->
        Text(stringResource(id), color = AxisLabel, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, modifier = m)
    }
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val side = grownBy(22.dp, 24f)
        // As wide as the screen allows, minus a label margin each side; never taller than the tab.
        val gridSize = min(maxWidth - side * 2, maxHeight - side * 2 - 80.dp).coerceAtLeast(120.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            label(R.string.square_exciting, Modifier.padding(bottom = 2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(side), contentAlignment = Alignment.Center) {
                    label(R.string.square_passionate, Modifier.vertical(-90f))
                }
                val description = stringResource(R.string.square_description)
                Canvas(
                    Modifier.size(gridSize)
                        .semantics { contentDescription = description }
                        .pointerInput(Unit) {
                            fun cellAt(o: Offset): Int {
                                val col = (o.x / size.width * MoodCell.SIZE).toInt().coerceIn(0, MoodCell.SIZE - 1)
                                val row = (o.y / size.height * MoodCell.SIZE).toInt().coerceIn(0, MoodCell.SIZE - 1)
                                return cellAtScreen(col, row).index
                            }
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val sel = linkedSetOf(cellAt(down.position))
                                currentOnTouch(sel.toSet())
                                haptics.tick()
                                down.consume()
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull() ?: break
                                    if (!change.pressed) { change.consume(); break }
                                    if (sel.add(cellAt(change.position))) {
                                        currentOnTouch(sel.toSet())
                                        haptics.tick()
                                    }
                                    change.consume()
                                }
                                currentOnPick(sel.toSet())
                            }
                        },
                ) { drawGrid(lit) }
                Box(Modifier.width(side), contentAlignment = Alignment.Center) {
                    label(R.string.square_joyful, Modifier.vertical(90f))
                }
            }
            label(R.string.square_calm, Modifier.padding(top = 2.dp))
            // Always takes its space, so the square doesn't jump when the message appears.
            Text(message.orEmpty(), color = Color.White, fontSize = 15.sp, maxLines = 2,
                textAlign = TextAlign.Center, minLines = 2,
                modifier = Modifier.padding(top = 10.dp, start = 24.dp, end = 24.dp))
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGrid(lit: Set<Int>) {
    val n = MoodCell.SIZE
    val cell = size.width / n
    val seam = 1.dp.toPx()
    // Unlit squares share one gradient, light at the top right, dark at the bottom left.
    drawRect(Brush.linearGradient(listOf(GridLight, GridDark), start = Offset(size.width, 0f), end = Offset(0f, size.height)))
    for (i in 0 until n * n) {
        if (i !in lit) continue
        val c = MoodCell.fromIndex(i)
        val topLeft = Offset(c.column() * cell, c.row() * cell)
        drawRect(GlowSeam, topLeft, Size(cell, cell))
        drawRect(
            Brush.radialGradient(
                0f to GlowCentre, 0.6f to GlowMiddle, 1f to GlowEdge,
                center = topLeft + Offset(cell / 2, cell / 2), radius = cell * 0.78f,
            ),
            topLeft + Offset(seam, seam), Size(cell - seam * 2, cell - seam * 2),
        )
    }
    // Bevelled seams between squares: a dark line with a faint light line beside it.
    for (k in 1 until n) {
        val p = k * cell
        drawRect(SeamDark, Offset(p - seam, 0f), Size(seam, size.height))
        drawRect(SeamLight, Offset(p, 0f), Size(seam, size.height))
        drawRect(SeamDark, Offset(0f, p - seam), Size(size.width, seam))
        drawRect(SeamLight, Offset(0f, p), Size(size.width, seam))
    }
    drawRect(GridEdge, Offset.Zero, Size(size.width, seam))
}
