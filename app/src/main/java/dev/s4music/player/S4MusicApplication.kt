package dev.s4music.player

import android.app.Application
import dev.s4music.player.data.AppSettings
import dev.s4music.player.data.Favourites
import dev.s4music.player.data.MediaStoreRepository
import dev.s4music.player.data.MoodStore
import dev.s4music.player.data.PlayStats
import dev.s4music.player.data.PlaylistStore
import dev.s4music.player.playback.OutputRoutes
import dev.s4music.player.playback.PlaybackBridge

class S4MusicApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        MediaStoreRepository.initLabels(this)
        Favourites.init(this)
        MoodStore.init(this)
        PlaylistStore.init(this)
        PlayStats.init(this)
        PlaybackBridge.init(this)
        OutputRoutes.init(this)
    }
}
