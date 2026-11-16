# Music Player (TouchWiz classic)

A from-scratch Kotlin rebuild of the classic Samsung TouchWiz music player (Galaxy S3 / S4 / Grand Quattro era) for modern Android (Android 11 and newer).

The original app's behaviour, layout and colours were studied from its APK, and the app was rewritten with Jetpack Compose and Media3. It contains no Samsung code.

## Features

- **Library:** TouchWiz icon tabs (Songs, Playlists, Albums, Artists, Genres, Folders), large song rows, a **# A–Z index bar** for jumping alphabetically, a shuffle row and search
- **Now Playing:** the TouchWiz layout with shuffle / repeat / A-B repeat, a seek bar, press-and-hold fast scrub, and a queue view
- **Mini player** at the bottom of the library
- **SoundAlive:** 13 presets plus a 7-band user EQ, with bass and 3D sliders
- **Play speed** (0.5x–2.0x) and **Music auto off** (sleep timer)
- **Playlists:** create, rename and delete, plus Most played, Recently played and Recently added
- **Edit track info:** rename the file and edit the title, artist, album, genre, year and **cover art** (one song or a whole album). Changes are written into the file itself.
- **Delete** songs, albums and folders (through Android's own confirmation)
- **Lyrics:** import `.lrc` / `.txt` files, read lyrics embedded in the file, or search online at [LRCLIB](https://lrclib.net). Online search only runs after the user agrees, and synced lyrics are highlighted line by line.
- **Home screen widget**, in the classic TouchWiz style
- **Background playback** through a Media3 `MediaSessionService`:
  - notification, lock screen and Bluetooth/headset controls
  - resumes the last queue
  - asks once for the battery-optimisation exemption

## Building

Requirements: JDK 17 and the Android SDK (platform 35). Android Studio works out of the box.

```bash
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the local debug key so it can be sideloaded. Set up your own signing config before distributing it.

Off-device screenshot tests (Robolectric + Roborazzi) render the main screens to `app/build/screens/`:

```bash
./gradlew testDebugUnitTest
```

## Project layout

| Path | Contents |
|---|---|
| `app/src/main/java/dev/s4music/player/data` | MediaStore library, playlists, play stats, tag/cover editing, lyrics |
| `.../playback` | `PlaybackService` (Media3), SoundAlive effects, A-B repeat and sleep timer |
| `.../widget` | Home screen widget |
| `.../ui` | Compose screens: library, now playing, dialogs |

## Disclaimer

This is an unofficial fan project, not affiliated with or endorsed by Samsung.

The launcher icon and the TouchWiz interface graphics (`app/src/main/res/drawable-*/tw_*.png`) were taken from Samsung's original Music Player APK. They are Samsung's copyrighted property and are included only to recreate the original look. All other code is original.

## License

The source code is released under the [MIT License](LICENSE). The license covers the original code only. It does **not** cover the Samsung graphics described above, which remain Samsung's property.

## Download

Get the latest APK from the [Releases](../../releases) page. It installs on Android 11 or newer; you'll need to allow installing from unknown sources.
