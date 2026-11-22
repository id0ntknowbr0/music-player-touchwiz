package dev.s4music.player.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Fills [MoodStore] for Music Square in the background. The quick pass reads BPM tags; the full
 * pass ([KEY_ANALYSE]) also decodes part of each song. Results are saved as it goes, so a run
 * that Android stops early picks up where it left off next time.
 */
class MoodWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        MoodStore.init(applicationContext)
        TagOptionSingleton.getInstance().isAndroid = true
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
        val analyse = inputData.getBoolean(KEY_ANALYSE, false)
        val songs = MediaStoreRepository.filter(MediaStoreRepository.loadSongs(applicationContext), AppSettings.current)
            .filter { it.path.isNotEmpty() }

        val todo = songs.filter { s ->
            val f = MoodStore.get(s.id)
            f == null || !f.tagsRead || (analyse && !f.analysed)
        }
        val pending = HashMap<Long, MoodFeatures>()
        todo.forEachIndexed { i, song ->
            if (isStopped) return@withContext Result.success().also { flush(pending) }
            var f = MoodStore.get(song.id) ?: MoodFeatures()
            if (!f.tagsRead) f = f.copy(tagsRead = true, bpmTag = readBpm(song.path))
            if (analyse && !f.analysed) {
                val r = MoodAnalyzer.analyze(song.path)
                f = f.copy(analysed = true, tempo = r?.tempo, rmsDb = r?.rmsDb, brightness = r?.brightness)
            }
            pending[song.id] = f
            if (pending.size >= 25) flush(pending)
            if (analyse) setProgress(workDataOf(KEY_DONE to i + 1, KEY_TOTAL to todo.size))
        }
        flush(pending)
        Result.success()
    }

    private fun flush(pending: HashMap<Long, MoodFeatures>) {
        MoodStore.put(pending.toMap())
        pending.clear()
        runCatching { MoodStore.save() }
    }

    private fun readBpm(path: String): Float? = runCatching {
        AudioFileIO.read(File(path)).tag?.getFirst(FieldKey.BPM)?.trim()?.replace(',', '.')?.toFloatOrNull()
            ?.takeIf { it in 30f..300f }
    }.getOrNull()

    companion object {
        private const val KEY_ANALYSE = "analyse"
        private const val KEY_DONE = "done"
        private const val KEY_TOTAL = "total"
        private const val QUICK = "mood_tags"
        private const val FULL = "mood_analysis"

        /** Null if WorkManager isn't available (it always is in the app; not in off-device tests). */
        private fun workManager(context: Context): WorkManager? = runCatching { WorkManager.getInstance(context) }.getOrNull()

        /** Reads BPM tags of songs not seen before. Cheap; runs whenever Music Square opens. */
        fun startQuickPass(context: Context) {
            workManager(context)?.enqueueUniqueWork(
                QUICK, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<MoodWorker>().build(),
            )
        }

        /** Decodes and measures every song not analysed yet. Waits while the battery is low. */
        fun startAnalysis(context: Context) {
            val request = OneTimeWorkRequestBuilder<MoodWorker>()
                .setInputData(workDataOf(KEY_ANALYSE to true))
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            workManager(context)?.enqueueUniqueWork(FULL, ExistingWorkPolicy.KEEP, request)
        }

        fun cancelAnalysis(context: Context) {
            workManager(context)?.cancelUniqueWork(FULL)
        }

        /** (done, total) while the full analysis is queued or running, otherwise null. */
        fun analysisProgress(context: Context): Flow<Pair<Int, Int>?> {
            val wm = workManager(context) ?: return flowOf(null)
            return wm.getWorkInfosForUniqueWorkFlow(FULL).map { infos ->
                val info = infos.firstOrNull { !it.state.isFinished } ?: return@map null
                if (info.state == WorkInfo.State.RUNNING) {
                    info.progress.getInt(KEY_DONE, 0) to info.progress.getInt(KEY_TOTAL, 0)
                } else 0 to 0
            }
        }
    }
}
