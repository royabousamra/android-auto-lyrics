# Car Lyrics

A personal Android Auto app. It follows whatever music app is playing on your phone, fetches synced lyrics from [LRCLIB](https://lrclib.net), and shows them karaoke-style on the car screen, in the spirit of Apple Music: big bold lines, words that fill in as they're sung, a springy scroll, and breathing dots during instrumental breaks.

It is meant for sideloading onto your own phone. It will never pass Play Store review (see "How it gets onto the car screen").

## How it works

```
 Spotify / YT Music / Apple Music / ...        lrclib.net
            │ media session                       ▲
            ▼                                     │ HTTPS (cached on disk)
 NowPlayingTracker ──track──▶ LyricsController ───┘
   (needs notification          │ LyricsState (synced / plain / not found)
    access)                     ▼
            position ──▶ KaraokeRenderer (Canvas) ──▶ Android Auto surface (SurfaceRenderer)
                                               └──▶ phone preview (KaraokeView)
```

| Piece | File | What it does |
|---|---|---|
| Now playing | `media/NowPlayingTracker.kt` | Uses `MediaSessionManager.getActiveSessions()` to read the title, artist, duration, cover art and playback position from any music app. It picks the session that most recently started playing. |
| Permission hook | `media/NowPlayingListenerService.kt` | An empty `NotificationListenerService`. Android only lets apps with notification access read other apps' media sessions. Notifications themselves are never read. |
| Lyrics | `lyrics/LrcLibClient.kt`, `lyrics/LyricsController.kt` | Tries an exact LRCLIB match (title, artist, album, duration), then cleaned-up searches ("- Remastered 2011", "feat. X" and similar get stripped). Results are cached in app storage so songs you've played before work without signal. |
| Parsing | `lyrics/LrcParser.kt` | Parses LRC files. Lyrics are highlighted line by line. Word-by-word highlighting is used only when the lyrics are in "enhanced LRC" with real per-word timestamps; word timing is never estimated. Long gaps become interludes. |
| Cover art | `art/CoverArtRepository.kt` | Takes the cover the music app embeds in its media session. If there isn't one, it tries the app's cover link (`content://` or `https://`). Only if both are missing does it look the song up on the iTunes Search API. |
| Colour theme | `art/ArtTheme.kt`, `art/Backdrop.kt` | AndroidX Palette picks the cover's tones: dark ones for the background, a bright one as accent (progress bar, tint of unsung lyrics). A soft-focus, darkened copy of the cover is used as the backdrop. |
| Drawing | `render/KaraokeRenderer.kt` | Plain `Canvas` drawing shared by the car and phone screens. Lyrics use the full screen; the cover fills the background behind them, slowly drifting, under a scrim so the text stays readable. A thin progress bar runs along the bottom edge. |
| Car screen | `car/LyricsScreen.kt`, `car/SurfaceRenderer.kt` | A `NavigationTemplate` with a small action strip (sync −0.25 s, sync +0.25 s, search again) on top of a surface the app repaints every frame. |

## How it gets onto the car screen (and the tradeoffs)

Android Auto doesn't let apps draw whatever they want. Third-party apps must use the Car App Library's fixed templates, and only some app categories get those. I looked at the options:

1. **Media app.** Media apps only provide a browse tree for *their own* audio, and Android Auto draws the player. You can't show lyrics for Spotify that way. Not viable.
2. **Text templates** (Pane/Message/LongMessage). These can show text, but the host limits how often a template can be refreshed, and you get no control over fonts or animation. You'd get a slideshow, not karaoke. Not viable.
3. **Navigation category with a map surface (what this app does).** Navigation apps receive a raw `Surface` for their map and may draw anything on it at full frame rate. Car Lyrics declares itself a navigation app and draws lyrics where the map would go.

What option 3 costs you:

- **It can't go through the Play Store's public listing**, which would reject a navigation app that doesn't navigate. And Android Auto only runs Car App Library apps that came from a trusted source such as Google Play. Its "Unknown sources" setting [does not apply to Car App Library apps](https://developer.android.com/training/cars/testing). See "Setting it up" for the ways around this.
- **It takes the navigation slot.** Android Auto shows one navigation app at a time. While Car Lyrics is open, Google Maps/Waze directions aren't on screen; you switch between them from the launcher, and Maps keeps navigating in the background with voice prompts. On the split-screen (Coolwalk) layout, lyrics fill the big panel and the media card stays in the side panel, which works well.
- **Android Auto updates could break it.** Google could tighten how unknown-source navigation apps are treated. The free install route in "Setting it up" also depends on how Android Auto currently checks where an app came from, which Google can change in any update. Only the Play route is documented by Google.
- **Driving safety is on you.** The host can't enforce its usual text limits on a free-drawn surface. Keep glances short.

## Building it

You need [Android Studio](https://developer.android.com/studio) (any recent version), or a GitHub repo with the included Actions workflow.

**Android Studio:** File → Open → pick this folder, let Gradle sync, plug in your phone with USB debugging on, and press Run. Or from a terminal: `./gradlew assembleDebug`. The APK ends up in `app/build/outputs/apk/debug/`. Run installs the app with adb. That's fine for the phone preview and the Desktop Head Unit, but the car won't list an adb-installed copy (see "Setting it up").

**GitHub Actions:** push this folder to a repo. The "Build APK" workflow runs the unit tests and uploads `car-lyrics-debug-apk` as a downloadable artifact on every push.

## Setting it up

1. **Install the APK so Android Auto trusts it.** Android Auto lists a Car App Library app only if Android's install record names Google Play as the installer *and* the install was started by Play or by the phone's own package installer. Each of these leaves Car Lyrics hidden in the car:
   - a plain `adb install`
   - `adb install -i com.android.vending`, because an adb install is always recorded as started by the shell
   - Android Studio's Run button
   - tapping the APK in a file manager

   Android Auto's "Unknown sources" setting doesn't help, because it [doesn't apply to Car App Library apps](https://developer.android.com/training/cars/testing). The Desktop Head Unit skips the check, which is why an adb install can work there but not in the car. These details come from community analysis of Android Auto 17.x; Google documents only the Play route. Use one of these:
   - **[UnknownAuto](https://github.com/GitMag/UnknownAuto)** (free, open source, no Play account). It hands the APK to the phone's own package installer with Google Play named as the installer, which is the record Android Auto looks for. It needs [Shizuku](https://shizuku.rikka.app).
     1. Install Shizuku from Google Play and start it. On Android 11+ you can start it through Wireless debugging (Shizuku walks you through it). From a computer, use the adb command Shizuku shows for that.
     2. Install UnknownAuto from its [releases page](https://github.com/GitMag/UnknownAuto/releases), for example with `adb install UnknownAuto-1.3.apk`.
     3. Copy Car Lyrics to the phone: `adb push app-debug.apk /sdcard/Download/`.
     4. Open UnknownAuto, tap **Load APK…**, pick the file, and tap **Install**. Allow Shizuku ("Allow all the time") and confirm the system install prompt. If Play Protect offers to scan the app, choose **More details → Install without scanning**. A ✔ banner means Android Auto will accept it.
   - **Google Play Internal App Sharing**, the route [Google documents](https://developer.android.com/training/cars/testing) for testing in a real car. It needs a Play developer account (one-time US$25 fee and identity verification) but no review.
     1. In Play Console, open Internal app sharing, upload the APK, and copy the link.
     2. On the phone, open Play Store → Settings → About, tap **Play Store version** seven times, then turn on **Internal app sharing**.
     3. Play re-signs shared APKs with its own test certificate, so uninstall the adb copy first with `adb uninstall com.roy.carlyrics`.
     4. Open the link on the phone and install.

   **Install updates the same way.** Installing over it with adb or Android Studio records the shell as the installer again, and Car Lyrics disappears from the car. If an update fails with a signature conflict, uninstall Car Lyrics first. APKs from different GitHub Actions runs can be signed with different throwaway debug keys, which causes this.

   To see what Android recorded, run `adb shell "dumpsys package com.roy.carlyrics | grep -E 'installerPackageName|initiatingPackageName'"`. Android Auto accepts `installerPackageName=com.android.vending` together with `initiatingPackageName=com.google.android.packageinstaller` (UnknownAuto) or `com.android.vending` (Play). If you see `initiatingPackageName=com.android.shell`, adb did the install and the car won't list the app.
2. **Open Car Lyrics on the phone** and tap "Grant notification access". Find Car Lyrics in the list and switch it on. If Android calls it a restricted setting, go to Settings → Apps → Car Lyrics, tap ⋮ → **Allow restricted settings**, and try again. Then play a song: lyrics should appear in the preview. If the timing is off, the Earlier/Later buttons adjust it (remembered per song).
3. **Restart Android Auto and connect to the car.** Android Auto remembers its verdict on each app until it restarts, so force-stop it (Settings → Apps → Android Auto → Force stop) or reboot the phone, then reconnect. Car Lyrics should appear in the launcher. If not, check "Customize launcher" in Android Auto settings.

If it still doesn't show up, check the install record with the `dumpsys package` command above first. Android Auto also logs why it turns an app away. After connecting to the car, run `adb shell "dumpsys activity service com.google.android.projection.gearhead | grep -i carlyrics"`. A line with `Package DENIED; failed all other checks [com.roy.carlyrics]` means it rejected the install source.

Car screen controls (top right): **−** shows lyrics earlier, **+** shows them later, **↻** searches LRCLIB again and skips the cache.

### Testing without a car

The Desktop Head Unit (DHU) skips Android Auto's install-source check, so an adb install works there. Install it from Android Studio's SDK Manager (SDK Tools → Android Auto Desktop Head Unit Emulator). Turn on Android Auto developer mode: Settings → Apps → Android Auto → Additional settings in the app (or search for "Android Auto" in Settings), scroll down, tap **Version** about 10 times, and accept the prompt. Then choose **Start head unit server** from the ⋮ menu and run:

```
adb forward tcp:5277 tcp:5277
$ANDROID_SDK/extras/google/auto/desktop-head-unit
```

## Known limitations

- **Most songs highlight line by line.** LRCLIB mostly has line timing only. Songs whose lyrics include word-level timing (enhanced LRC) fill in word by word automatically.
- **Sync relies on the music app.** Apps report position correctly in general, but Bluetooth or car audio delay can make the lyrics run a little early. Use **+** once and it's remembered for that song.
- **LRCLIB coverage** is very good for popular music and patchy for obscure tracks. Unsynced lyrics are shown as slow-scrolling plain text.
- **Video or podcast apps** also publish media sessions, so a playing YouTube video will be picked up as well.

## Tests

`./gradlew testDebugUnitTest` runs the JVM tests for the LRC parser and title cleanup. It also runs `ScreenshotTest`, which renders the car and phone screens with sample covers into `app/build/screenshots/*.png` using Robolectric. The GitHub workflow uploads those images as the `screenshots` artifact, so you can see UI changes without a car.
