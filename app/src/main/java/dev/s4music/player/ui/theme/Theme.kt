package dev.s4music.player.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Colours lifted from the S4 music player's res/values/colors.xml (TouchWiz Nature UX dark). */
object S4Colors {
    val Background = Color(0xFF0E0E0E)
    val Surface = Color(0xFF1B1B1B)          // music_player_lyric_text_background
    val TabBar = Color(0xFF1E2124)
    val CountInfoBg = Color(0xFF313131)      // music_player_list_conut_info_background
    val Divider = Color(0xFF262626)          // music_player_list_shuffle_divider
    val Accent = Color(0xFF2FA6C9)           // music_player_list_playing
    val AccentBright = Color(0xFF009CFF)     // music_player_played_time
    val AlbumText = Color(0xFF0091FF)        // music_player_album_color
    val Matched = Color(0xFF21CDFF)          // matched_text_color (search)
    val Text1 = Color(0xFFD7D7D7)            // music_player_list_text1
    val Text2 = Color(0xFF898989)            // music_player_list_text2
    val ListDuration = Color(0xFF387586)     // music_player_list_duration
    val Duration = Color(0xFFB4B4B4)         // music_player_duration
    val PlayerTitle = Color(0xFFF0F0F0)      // music_player_list_player_title
    val PlayerArtist = Color(0xFFD2D2D2)     // music_player_list_player_artist
    val ControlIcon = Color(0xFFD0D0D0)      // music_player_control_release_btn_color
    val Disabled = Color(0xFF5B6872)         // equalizer_disabled_text_color

    val HeaderGradient = Brush.verticalGradient(listOf(Color(0xFF3B4146), Color(0xFF1A1D20)))
    val ControlBarGradient = Brush.verticalGradient(listOf(Color(0xFF2C3135), Color(0xFF0F1113)))
    val PlayerBackground = Brush.verticalGradient(listOf(Color(0xFF22292E), Color(0xFF0A0C0D)))
    val ArtPlaceholder = Brush.linearGradient(listOf(Color(0xFF2B3A44), Color(0xFF101418)))
    val PlayButton = Brush.verticalGradient(listOf(Color(0xFF3FB8F0), Color(0xFF0A7BC2)))
}

@Composable
fun S4Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = S4Colors.Accent,
            onPrimary = Color.White,
            secondary = S4Colors.AccentBright,
            background = S4Colors.Background,
            onBackground = S4Colors.Text1,
            surface = S4Colors.Surface,
            onSurface = S4Colors.Text1,
            surfaceVariant = S4Colors.CountInfoBg,
            onSurfaceVariant = S4Colors.Text2,
            surfaceContainerHigh = Color(0xFF26292C),
            outline = S4Colors.Text2,
        ),
        content = content,
    )
}
