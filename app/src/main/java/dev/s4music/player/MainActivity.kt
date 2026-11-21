package dev.s4music.player

import android.Manifest
import android.app.SearchManager
import android.provider.MediaStore
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.s4music.player.ui.BackgroundPlayback
import dev.s4music.player.ui.LibraryViewModel
import dev.s4music.player.ui.PlayerViewModel
import dev.s4music.player.ui.S4MusicApp
import dev.s4music.player.ui.theme.S4Colors
import dev.s4music.player.ui.theme.S4Theme

class MainActivity : ComponentActivity() {
    private val openPlayer = mutableStateOf(false)
    private val externalUri = mutableStateOf<Uri?>(null)
    /** Voice search query ("" = "play some music"); null when there's none to handle. */
    private val playQuery = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            S4Theme {
                val libraryVm: LibraryViewModel = viewModel()
                val playerVm: PlayerViewModel = viewModel()

                val library by libraryVm.library.collectAsStateWithLifecycle()
                val loading by libraryVm.loading.collectAsStateWithLifecycle()
                LaunchedEffect(playQuery.value, loading) {
                    val query = playQuery.value ?: return@LaunchedEffect
                    if (loading) return@LaunchedEffect   // wait for the library
                    playerVm.playFromSearch(query, library)
                    playQuery.value = null
                }

                LaunchedEffect(externalUri.value) {
                    externalUri.value?.let {
                        playerVm.playUri(it)
                        openPlayer.value = true
                        externalUri.value = null
                    }
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(S4Colors.Background)
                        .systemBarsPadding()
                ) {
                    PermissionGate(onGranted = { libraryVm.start() }) {
                        S4MusicApp(libraryVm, playerVm, openPlayer)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) openPlayer.value = true
        if (intent.action == Intent.ACTION_VIEW) intent.data?.let { externalUri.value = it }
        if (intent.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            playQuery.value = intent.getStringExtra(SearchManager.QUERY).orEmpty()
        }
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }
}

@Composable
private fun PermissionGate(onGranted: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE
    val requested = remember {
        if (Build.VERSION.SDK_INT >= 33) arrayOf(audioPermission, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(audioPermission)
    }
    fun check() = ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(check()) }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        granted = check()
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(requested) }
    LifecycleResumeEffect(Unit) {
        granted = check()
        onPauseOrDispose { }
    }
    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        onGranted()
        // First launch only: ask Android (via its own system dialog) to exempt the app from
        // battery optimisation, so phones with aggressive battery savers don't stop playback.
        if (!BackgroundPlayback.isExempt(context) && !BackgroundPlayback.wasPrompted(context)) {
            BackgroundPlayback.markPrompted(context)
            BackgroundPlayback.requestExemption(context)
        }
    }

    if (granted) {
        content()
    } else {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.LibraryMusic, null, tint = S4Colors.Accent, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.permission_rationale),
                color = S4Colors.Text1, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { launcher.launch(requested) },
                colors = ButtonDefaults.buttonColors(containerColor = S4Colors.Accent),
            ) { Text(stringResource(R.string.permission_allow)) }
            if (asked) {
                TextButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null))
                    )
                }) { Text(stringResource(R.string.open_app_settings), color = S4Colors.Text2) }
            }
        }
    }
}
