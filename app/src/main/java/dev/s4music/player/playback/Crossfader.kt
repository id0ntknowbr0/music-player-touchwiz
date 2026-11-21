package dev.s4music.player.playback

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Crossfades between songs with a second, short-lived player.
 *
 * The session's [player] stays in charge of the queue. Shortly before the end of a song, a
 * helper player starts the *same* song muted and is lined up with the main player's position.
 * At the start of the fade the two swap: the helper carries the outgoing song's tail and fades
 * out, while the main player moves to the next song and fades in. The notification, queue and
 * play counts move on at the start of the fade.
 *
 * The helper shares the main player's audio session, so SoundAlive effects apply to both.
 * With crossfade off nothing here runs, and ExoPlayer's gapless playback is untouched.
 */
@OptIn(UnstableApi::class)
class Crossfader(
    private val player: ExoPlayer,
    private val scope: CoroutineScope,
    private val newHelperPlayer: () -> ExoPlayer,
    /** Crossfade length in ms; 0 = off. */
    private val fadeMs: () -> Long,
    /** Other features that rule out a crossfade right now (repeat one, A-B repeat, "after current song"). */
    private val allowed: () -> Boolean,
    /** Volume the outgoing song is playing at (Smart volume, sleep fade) when the helper takes over. */
    private val outgoingVolume: () -> Float,
    /** Multiplier for the main player's volume while the next song fades in. */
    private val setFadeIn: (Float) -> Unit,
) {
    private var monitor: Job? = null
    private var fade: Job? = null

    fun start() {
        if (monitor != null) return
        monitor = scope.launch {
            while (isActive) {
                delay(nextCheckDelay())
            }
        }
    }

    /** Ends any fade in progress at once (the user skipped, paused or stopped). */
    fun cancel() {
        fade?.cancel()
        fade = null
    }

    /** Checks whether a fade should begin; returns how long to wait before checking again. */
    private fun nextCheckDelay(): Long {
        val length = fadeMs()
        if (length <= 0 || fade?.isActive == true || !player.isPlaying || !allowed() || !player.hasNextMediaItem()) return 250
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration < length * 3) return 250
        val remaining = realMs(duration - player.currentPosition)
        if (remaining <= length + PREPARE_MS) {
            val index = player.currentMediaItemIndex
            fade = scope.launch { runFade(index, length) }
            return 250
        }
        return if (remaining < length + PREPARE_MS + 1500) 40 else 250
    }

    private fun realMs(mediaMs: Long) = (mediaMs / player.playbackParameters.speed).toLong()

    private suspend fun runFade(index: Int, length: Long) {
        val item = player.currentMediaItem ?: return
        val duration = player.duration
        fun stillOnSong() = player.currentMediaItemIndex == index && player.isPlaying && fadeMs() > 0 &&
            allowed() && player.hasNextMediaItem()

        val helper = newHelperPlayer()
        try {
            helper.setAudioSessionId(player.audioSessionId)
            helper.playbackParameters = player.playbackParameters
            helper.volume = 0f
            helper.setMediaItem(item, player.currentPosition + 200)
            helper.prepare()
            helper.play()
            if (withTimeoutOrNull(2_000) { while (!helper.isPlaying) delay(10) } == null) return

            // Line the muted helper up with the main player (both positions are what's audible now).
            // Each seek takes a moment to restart, so aim ahead by the delay seen last time.
            var lead = 200L
            for (attempt in 0 until 5) {
                delay(120)
                if (!stillOnSong()) return
                val drift = player.currentPosition - helper.currentPosition
                if (abs(drift) <= ALIGNED_MS) break
                lead += drift
                helper.seekTo(player.currentPosition + lead.coerceIn(0, 1_000))
                if (withTimeoutOrNull(1_000) { while (!helper.isPlaying) delay(5) } == null) return
            }

            // Wait for the fade point.
            while (true) {
                if (!stillOnSong()) return
                if (realMs(duration - player.currentPosition) <= length) break
                delay(10)
            }

            // Swap: helper takes the outgoing tail, main player starts the next song silently.
            val outVolume = outgoingVolume()
            helper.volume = outVolume
            setFadeIn(0f)
            player.seekToNextMediaItem()
            val newIndex = player.currentMediaItemIndex
            val start = SystemClock.elapsedRealtime()
            val inLengthMedia = length * player.playbackParameters.speed

            while (true) {
                val tOut = ((SystemClock.elapsedRealtime() - start).toFloat() / length).coerceIn(0f, 1f)
                // Fade in by the new song's own position, so its start-up delay doesn't cut the fade short.
                val tIn = (player.currentPosition / inLengthMedia).coerceIn(0f, 1f)
                helper.volume = outVolume * cos(tOut * PI / 2).toFloat()
                setFadeIn(sin(tIn * PI / 2).toFloat())
                if (tOut >= 1f && tIn >= 1f) break
                if (player.currentMediaItemIndex != newIndex || !player.playWhenReady) break
                if (helper.playbackState == Player.STATE_ENDED && tIn >= 1f) break
                delay(30)
            }
        } finally {
            helper.release()
            setFadeIn(1f)
        }
    }

    fun release() {
        monitor?.cancel(); monitor = null
        cancel()
    }

    private companion object {
        /** How long before the fade the helper starts (to buffer and line up). */
        const val PREPARE_MS = 2_500L
        const val ALIGNED_MS = 12L
    }
}
