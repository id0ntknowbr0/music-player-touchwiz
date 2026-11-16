package dev.s4music.player

import android.app.Application
import dev.s4music.player.data.PlayStats
import dev.s4music.player.data.PlaylistStore
import dev.s4music.player.playback.PlaybackBridge

class S4MusicApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PlaylistStore.init(this)
        PlayStats.init(this)
        PlaybackBridge.init(this)
    }
}
