# CW Trainer

An offline Android Morse code trainer: Koch method, Farnsworth spacing, spaced repetition,
realistic band conditions, and send practice on a touchscreen key.

Kotlin + Jetpack Compose, minSdk 26, no internet permission.

## Version 2.1

Added on top of version 1 (nothing removed):

- **Background listening**: 10, 20 or 30 minute sessions drawn from the spaced-repetition
  queue (groups, words, callsigns, QSO text, as your Koch lesson allows), a configurable gap
  after each item and an optional on-screen reveal. Keeps playing with the screen off through
  a foreground service with a media notification (play/pause, skip, stop); handles audio
  focus and pauses when headphones are unplugged.
- **Home-screen widget** (Glance): streak, today's minutes against the 15-minute goal, Koch
  lesson, and a "Quick 2-min drill" button that opens straight into a session.
- **Export and backup** (Settings > Export and backup): three CSV files through the share
  sheet or into a folder you pick, and a JSON full backup with restore.

### Permissions in 2.1

Version 1 had none. Background playback cannot be done without these four:

| Permission | Why |
|---|---|
| FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK | Keep the listening session alive with the screen off |
| WAKE_LOCK | Stop the CPU sleeping mid-session (held only while sound is playing) |
| POST_NOTIFICATIONS | Show the playback controls on Android 13+ (asked the first time you start a session) |

Still no INTERNET permission. The widget library (Glance) brings in WorkManager, whose
manifest asks for ACCESS_NETWORK_STATE and RECEIVE_BOOT_COMPLETED; both are stripped again in
`AndroidManifest.xml`. If the widget ever misbehaves, that removal is the first thing to
suspect. Export needs no storage permission: files go through the share sheet or the system
file picker.

## Build status: read this first

Version 1 was built by GitHub Actions and installs. The 2.1 additions were written without
access to the Android SDK, in the same way as version 1:

| What | Status |
|---|---|
| `core` additions (playlist generator, CSV, JSON backup) | Compiled with Kotlin 2.2.20. All 69 unit tests pass, including the export/restore round trip. |
| `app` additions (service, notification, widget, export screens) | Type-checked against hand-written stand-ins for the Android and Glance APIs. Not yet compiled against the real SDK. |
| Screen-off playback, notification controls, audio focus, unplugging headphones, the widget, share sheet, restore | Not run on a device. |

## Get the APK

### Option A: GitHub Actions (no local setup)

1. Create an empty GitHub repository and push this folder to its `main` branch.
2. Open the repository's **Actions** tab. The *Build APK* workflow runs on every push
   (or start it by hand with *Run workflow*).
3. When it finishes, download the **CwTrainer-apk** artifact. It contains `app-release.apk`.

The workflow runs the unit tests, builds the signed release APK, and fails if the APK
contains `android.permission.INTERNET`.

### Option B: on your own computer

You need JDK 17 and the Android SDK (Android Studio installs both). Then:

```
./gradlew :core:test            # unit tests, no Android SDK needed
./gradlew :app:assembleRelease  # APK lands in app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

If Gradle cannot find the SDK, create `local.properties` with `sdk.dir=/path/to/Android/Sdk`.

### Signing

Release and debug builds are both signed with `keystore/sideload.jks` (password `cwtrainer`,
committed on purpose). Every build therefore has the same signature and installs over the
previous one without wiping your progress. It is a sideload key, not a secret: replace it
before distributing the app to anyone else.

### SDK level

`compileSdk` and `targetSdk` are 36 (Android 16). Android 17 (API 37) needs Android Gradle
Plugin 9.x, which changes the build scripts; that upgrade was left out because it could not
be tested here.

## Checks to run on your phone

The brief asked for these before delivery. They need a device, so they are still open:

1. The APK installs.
2. Everything works in airplane mode.
3. Tones have no clicks, also on headphones, at 15 and 30 WPM.
4. The four band presets sound realistic to you. `previews/band-presets-preview.wav` is the
   engine's own output for Clean, Mild, Real band and Contest, 13 seconds each, so you can
   judge them before installing anything.
5. Reaction times in the instant-recognition drill feel right. The app subtracts an estimate
   of the audio output latency; on Bluetooth headphones the real latency is much larger and
   reaction times will read high.

## How the training works

- **Koch order**: K M U R E S N A P T L W I . J Z = F O Y , V G 5 / Q 9 2 H 3 8 B ? 4 7 C 1 D 6 0 X.
  You start with K and M. The next character unlocks after a receive session of at least
  5 minutes with 90 % or better (and at least 40 characters copied).
- **Farnsworth**: characters always play at the character speed (default 20 WPM, 15 to 30).
  Effective speed starts at 8 WPM and rises linearly with each unlocked character until it
  equals the character speed at the last one. Settings has an override.
- **Timing**: PARIS standard. Dot = 1200 / WPM ms. Farnsworth spacing per word is
  `t_a = (60c - 37.2s) / (sc)` seconds, split 3/19 per character gap and 7/19 per word gap.
- **Sound only**: dot/dash patterns appear on the Reference screen and nowhere else.
- **Spaced repetition**: every character, word, prosign and Q-code/abbreviation is an SM-2
  item. A miss resets the item and brings it back within the session; slow answers lower its
  ease. Because drills repeat an item many times in one sitting, the interval only advances
  when the item was actually due. Every drill draws from the same scheduler.
- **Unlocks**: Receive, Instant characters, Send and Reference are always available. Head copy
  opens at 6 characters and widens from groups to words to sentences. Instant words, Prosigns,
  Real-world copy and fist simulation open when all letters are learned. X is the last
  character in Koch order, so that is the final lesson. Settings lets you set the lesson by
  hand if you already know some characters.

## Project structure

```
core/   Pure Kotlin, no Android imports. Everything here runs and is tested on a desktop JVM.
  morse/    MorseCode (table, prosigns), Timing (PARIS + Farnsworth), Keyer (text -> key
            down/up times), FistModel (human timing imperfections)
  audio/    CwEngine (software mixer), CwVoice (keyed oscillator, 5 ms raised-cosine edges),
            Noise (band-limited noise + static crashes), BandConditions (presets)
  send/     SendDecoder (adaptive decoder + timing score), IambicKeyer (mode B)
  koch/     Koch (order, unlock rule, effective-speed schedule)
  srs/      Sm2 (review maths), SrsScheduler (weighted choice of what to drill next)
  content/  word lists, Q-codes, abbreviations, callsign generator, QSO generator
  drill/    DrillType interface + the seven drills, Scoring (alignment), Recommender
  listen/   PlaylistGenerator (listening material from the queue)
  export/   CsvExport, BackupCodec (JSON backup), Json (tiny reader/writer)
  test/     TimingTest, KochTest, SrsTest, SendTest, DrillTest, AudioTest, ExportTest, PlaylistTest

app/    Android only.
  audio/    AudioPlayer: a thread that pulls samples from CwEngine into a low-latency AudioTrack
  data/     Room (srs_items, sessions), DataStore (settings), Repository, Exporter (share sheet
            and file picker glue)
  session/  DrillController (runs a receive session), SendController (key input -> decoder)
  listen/   ListeningController (the session: queue, gaps, clock), ListeningService (foreground
            service, media notification, audio focus, wake lock, headphone unplug)
  widget/   CwWidget (Glance)
  ui/       Compose screens: Home, Drill (copy / recognise / head copy / summary), Send,
            Progress, Reference, Settings; shared Components and theme
```

### Audio path

`AudioPlayer` asks `CwEngine.render()` for mono float samples and writes them to a streaming
`AudioTrack` (USAGE_MEDIA, low-latency mode, device-native sample rate). Nothing is read from
media files. The engine mixes, per sample: the wanted signal (with QSB fading, drift and
chirp), the sidetone for the on-screen key, a QRM station sending unrelated text at an offset
pitch, and noise shaped by a two-stage band-pass around the listening pitch at an exact SNR,
plus static crashes. Mixer gain is set from the preset so only static crashes reach the soft
limiter.

### Privacy

The manifest explicitly removes `INTERNET`, `ACCESS_NETWORK_STATE` and
`RECEIVE_BOOT_COMPLETED` (`tools:node="remove"`) so no library can add them.
No analytics, ads, accounts or network code exist anywhere in the project.

## Tests

`./gradlew :core:test` covers:

- **Timing**: dot length, standard and Farnsworth gaps against the formula, exact
  effective speed for PARIS at several speed pairs, prosigns sent without letter gaps.
- **Send decoder**: perfect sending from 8 to 30 WPM, human fists, speed changes, prosigns,
  unknown patterns, timing score and ratios; iambic keyer element timing, squeeze, mode B,
  dot memory.
- **Spaced repetition**: SM-2 intervals, no interval inflation from cramming, lapses,
  ease bounds, weak items measurably shown more often than mastered ones.
- **Koch**: order, unlock boundaries (accuracy, duration, minimum characters), effective-speed
  schedule.
- **Drills and content**: only unlocked characters are used, 100 unique words, every string
  is sendable, Turkish TA/TB/YM callsigns, unlock rules, answer alignment.
- **Export and restore**: a backup decodes to exactly what was encoded and re-encodes
  byte-for-byte; exact doubles and large numbers; foreign, truncated, damaged and newer-format
  files are rejected with a readable message; CSV rows, values, quoting and grouping.
- **Playlists**: only unlocked material, kinds widen with the lesson, weak characters are
  heard more often, a playlist fills the requested time.
- **Audio**: element lengths at half amplitude, out-of-band energy of shaped keying versus
  hard keying (the click test), silence between elements, measured SNR against the requested
  SNR, QSB depth, QRM pitch and level, no clipping in any preset.

## Still on the roadmap

| Feature | Where it goes | What already exists for it |
|---|---|---|
| Microphone CW decoder | New `app/audio/MicDecoder` (AudioRecord + tone detector) | It only has to call `SendDecoder.keyDown/keyUp/tick`. Needs RECORD_AUDIO. |
| Physical key or paddle (USB-OTG, Bluetooth) | Key-event handler in `MainActivity` forwarding to `SendController.straight()` / `paddle()` | Those two functions are the whole input abstraction. |
| Pile-up simulator | New `PileupDrill : DrillType` in `core/drill`, added to `Drills.all` | `CwEngine` mixes any number of `CwVoice`s; QRM is already a second voice. |
| Turkish characters (Ç, Ğ, Ş, Ö, Ü) | Entries in `MorseCode.characters` plus an optional extension of `Koch.ORDER` | Symbols are strings throughout. |
| QSO role-play | New `QsoRoleplayDrill` with `DrillMode.SEND` | `Qso` generates the other station's lines, `SendDecoder` reads your reply. |
