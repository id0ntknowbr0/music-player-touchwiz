package dev.s4music.player.playback

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter2
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OutputKind { SPEAKER, WIRED, BLUETOOTH, USB }

/** Where music is playing now. [key] identifies the output for per-output SoundAlive. */
data class OutputRoute(val kind: OutputKind, val name: String?, val key: String)

/** Tracks the current audio output (speaker, wired headphones, a Bluetooth device…). */
object OutputRoutes {
    private val SPEAKER = OutputRoute(OutputKind.SPEAKER, null, "speaker")
    private val _current = MutableStateFlow(SPEAKER)
    val current: StateFlow<OutputRoute> = _current.asStateFlow()
    private lateinit var audio: AudioManager

    fun init(context: Context) {
        if (::audio.isInitialized) return
        audio = context.getSystemService(AudioManager::class.java)
        audio.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refresh()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refresh()
        }, Handler(Looper.getMainLooper()))
        refresh()
    }

    fun refresh() {
        _current.value = runCatching { detect() }.getOrDefault(SPEAKER)
    }

    private fun detect(): OutputRoute {
        if (Build.VERSION.SDK_INT >= 33) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            audio.getAudioDevicesForAttributes(attrs).firstNotNullOfOrNull(::routeOf)?.let { return it }
        }
        // Android 11-12: no direct API for "where media goes", so use the usual priority.
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapNotNull(::routeOf)
        return outputs.firstOrNull { it.kind == OutputKind.BLUETOOTH }
            ?: outputs.firstOrNull { it.kind == OutputKind.WIRED }
            ?: outputs.firstOrNull { it.kind == OutputKind.USB }
            ?: SPEAKER
    }

    private fun routeOf(d: AudioDeviceInfo): OutputRoute? {
        val name = d.productName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        return when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_LINE_ANALOG ->
                OutputRoute(OutputKind.WIRED, null, "wired")
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_HEARING_AID -> OutputRoute(OutputKind.BLUETOOTH, name, "bt:${name.orEmpty()}")
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY ->
                OutputRoute(OutputKind.USB, name, "usb:${name.orEmpty()}")
            else -> null
        }
    }

    /**
     * Opens Android's own output switcher (Bluetooth, wired, and Cast devices that the system can
     * route to). Each Android version exposes it differently; falls back to Bluetooth settings.
     */
    fun openSystemOutputSwitcher(context: Context) {
        val pkg = context.packageName
        if (Build.VERSION.SDK_INT >= 34 &&
            runCatching { MediaRouter2.getInstance(context).showSystemOutputSwitcher() }.getOrDefault(false)
        ) return
        if (Build.VERSION.SDK_INT >= 31) {
            val ok = runCatching {
                context.sendBroadcast(
                    Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
                        .setPackage("com.android.systemui")
                        .putExtra("package_name", pkg)
                )
            }.isSuccess
            if (ok) return
        }
        val panel = Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
            .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(panel) }.isSuccess) return
        runCatching {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
