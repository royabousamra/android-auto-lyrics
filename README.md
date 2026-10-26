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
| Parsing | `lyrics/LrcParser.kt` | Parses LRC files, including word-level "enhanced LRC". LRCLIB is usually line-level, so word timing is estimated from word length. Long gaps become interludes. |
| Drawing | `render/KaraokeRenderer.kt` | Plain `Canvas` drawing shared by the car and phone screens. The background colour comes from the cover art. |
| Car screen | `car/LyricsScreen.kt`, `car/SurfaceRenderer.kt` | A `NavigationTemplate` with a small action strip (sync −0.25 s, sync +0.25 s, search again) on top of a surface the app repaints every frame. |

## How it gets onto the car screen (and the tradeoffs)

Android Auto doesn't let apps draw whatever they want. Third-party apps must use the Car App Library's fixed templates, and only some app categories get those. I looked at the options:

1. **Media app.** Media apps only provide a browse tree for *their own* audio, and Android Auto draws the player. You can't show lyrics for Spotify that way. Not viable.
2. **Text templates** (Pane/Message/LongMessage). These can show text, but the host limits how often a template can be refreshed, and you get no control over fonts or animation. You'd get a slideshow, not karaoke. Not viable.
3. **Navigation category with a map surface (what this app does).** Navigation apps receive a raw `Surface` for their map and may draw anything on it at full frame rate. Car Lyrics declares itself a navigation app and draws lyrics where the map would go.

What option 3 costs you:

- **It only works sideloaded**, with Android Auto's "Unknown sources" developer setting turned on. The Play Store would reject a navigation app that doesn't navigate.
- **It takes the navigation slot.** Android Auto shows one navigation app at a time. While Car Lyrics is open, Google Maps/Waze directions aren't on screen; you switch between them from the launcher, and Maps keeps navigating in the background with voice prompts. On the split-screen (Coolwalk) layout, lyrics fill the big panel and the media card stays in the side panel, which works well.
- **Android Auto updates could break it.** Google could tighten how unknown-source navigation apps are treated. This approach has worked for years for community apps, but nothing guarantees it.
- **Driving safety is on you.** The host can't enforce its usual text limits on a free-drawn surface. Keep glances short.

## Building it

You need [Android Studio](https://developer.android.com/studio) (any recent version), or a GitHub repo with the included Actions workflow.

**Android Studio:** File → Open → pick this folder, let Gradle sync, plug in your phone with USB debugging on, and press Run. Or from a terminal: `./gradlew assembleDebug`. The APK ends up in `app/build/outputs/apk/debug/`.

**GitHub Actions:** push this folder to a repo. The "Build APK" workflow runs the unit tests and uploads `car-lyrics-debug-apk` as a downloadable artifact on every push.

## Setting it up

1. **Install the APK** on your phone (from Android Studio, `adb install`, or by opening the file and allowing installs from that source).
2. **Open Car Lyrics on the phone** and tap "Grant notification access". Find Car Lyrics in the list and switch it on. Then play a song: lyrics should appear in the preview. If the timing is off, the Earlier/Later buttons adjust it (remembered per song).
3. **Turn on Android Auto developer mode:** Settings → Apps → Android Auto → Additional settings in the app (or search for "Android Auto" in Settings). Scroll down and tap **Version** about 10 times, then accept the prompt.
4. **Allow unknown sources:** in that same Android Auto settings screen, open the ⋮ menu → **Developer settings** → enable **Unknown sources**.
5. **Connect to the car.** Car Lyrics should appear in the Android Auto launcher. If not, open "Customize launcher" in Android Auto settings and enable it there. Then reconnect.

Car screen controls (top right): **−** shows lyrics earlier, **+** shows them later, **↻** searches LRCLIB again and skips the cache.

### Testing without a car

Install the Desktop Head Unit from Android Studio's SDK Manager (SDK Tools → Android Auto Desktop Head Unit Emulator). In Android Auto developer settings, choose "Start head unit server". Then run:

```
adb forward tcp:5277 tcp:5277
$ANDROID_SDK/extras/google/auto/desktop-head-unit
```

## Known limitations

- **Word timing is usually estimated.** LRCLIB mostly has line timing, so the fill within a line is a good guess, not exact. Songs that have word-level timing in LRCLIB use it automatically.
- **Sync relies on the music app.** Apps report position correctly in general, but Bluetooth or car audio delay can make the lyrics run a little early. Use **+** once and it's remembered for that song.
- **LRCLIB coverage** is very good for popular music and patchy for obscure tracks. Unsynced lyrics are shown as slow-scrolling plain text.
- **Video or podcast apps** also publish media sessions, so a playing YouTube video will be picked up as well.

## Tests

`./gradlew testDebugUnitTest` runs the JVM tests for the LRC parser and title cleanup.
